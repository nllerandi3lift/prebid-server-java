package org.prebid.server.settings.helper;

import io.vertx.core.json.JsonArray;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.ResultSet;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.settings.jdbc.model.SqlQuery;
import org.prebid.server.settings.model.StoredDataResult;
import org.prebid.server.settings.model.StoredDataType;
import org.prebid.server.settings.model.StoredItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Component responsible for creating SQL queries and mapping result set results to application specific data objects.
 */
public class JdbcQueryTranslator {

    private static final Logger logger = LoggerFactory.getLogger(JdbcQueryTranslator.class);

    private static final String ACCOUNT_ID_PLACEHOLDER = "%ACCOUNT_ID%";
    private static final String REQUEST_ID_PLACEHOLDER = "%REQUEST_ID_LIST%";
    private static final String IMP_ID_PLACEHOLDER = "%IMP_ID_LIST%";
    private static final String RESPONSE_ID_PLACEHOLDER = "%RESPONSE_ID_LIST%";
    private static final String QUERY_PARAM_PLACEHOLDER = "?";

    private static final String SELECT_ADUNIT_CONFIG_QUERY =
            "SELECT config FROM s2sconfig_config where uuid = ? LIMIT 1";

    /**
     * Query to select account by ids.
     */
    private final String selectAccountQuery;

    /**
     * Query to select stored requests and imps by ids, for example:
     * <pre>
     * SELECT accountId, reqid, requestData, 'request' as dataType
     *   FROM stored_requests
     *   WHERE reqid in (%REQUEST_ID_LIST%)
     * UNION ALL
     * SELECT accountId, impid, impData, 'imp' as dataType
     *   FROM stored_imps
     *   WHERE impid in (%IMP_ID_LIST%)
     * </pre>
     */
    private final String selectStoredRequestsQuery;

    /**
     * Query to select amp stored requests by ids, for example:
     * <pre>
     * SELECT accountId, reqid, requestData, 'request' as dataType
     *   FROM stored_requests
     *   WHERE reqid in (%REQUEST_ID_LIST%)
     * </pre>
     */
    private final String selectAmpStoredRequestsQuery;

    /**
     * Query to select stored responses by ids, for example:
     * <pre>
     * SELECT respid, responseData
     *   FROM stored_responses
     *   WHERE respid in (%RESPONSE_ID_LIST%)
     * </pre>
     */
    private final String selectStoredResponsesQuery;

    public JdbcQueryTranslator(String selectAccountQuery,
                               String selectStoredRequestsQuery,
                               String selectAmpStoredRequestsQuery,
                               String selectStoredResponsesQuery) {

        this.selectAccountQuery = Objects.requireNonNull(selectAccountQuery)
                .replace(ACCOUNT_ID_PLACEHOLDER, QUERY_PARAM_PLACEHOLDER);
        this.selectStoredRequestsQuery = Objects.requireNonNull(selectStoredRequestsQuery);
        this.selectAmpStoredRequestsQuery = Objects.requireNonNull(selectAmpStoredRequestsQuery);
        this.selectStoredResponsesQuery = Objects.requireNonNull(selectStoredResponsesQuery);
    }

    public SqlQuery selectAccountQuery(String accountId) {
        return SqlQuery.of(selectAccountQuery, Collections.singletonList(accountId));
    }

    public SqlQuery selectAdUnitConfigQuery(String adUnitConfigId) {
        return SqlQuery.of(SELECT_ADUNIT_CONFIG_QUERY, Collections.singletonList(adUnitConfigId));
    }

    public SqlQuery selectStoredRequestsQuery(Set<String> requestIds, Set<String> impIds) {
        return createStoredRequestQuery(selectStoredRequestsQuery, requestIds, impIds);
    }

    public SqlQuery selectAmpStoredRequestsQuery(Set<String> requestIds, Set<String> impIds) {
        return createStoredRequestQuery(selectAmpStoredRequestsQuery, requestIds, impIds);
    }

    public SqlQuery selectStoredResponsesQuery(Set<String> responseIds) {
        return createStoredResponseQuery(selectStoredResponsesQuery, responseIds);
    }

    /**
     * Maps {@link ResultSet} to {@link StoredDataResult} and creates an error for each missing ID and add it to result.
     *
     * @param resultSet  - incoming Result Set representing a result of SQL query
     * @param accountId  - an account ID extracted from request
     * @param requestIds - a specified set of stored requests' IDs. Adds error for each ID missing in result set
     * @param impIds     - a specified set of stored imps' IDs. Adds error for each ID missing in result set
     * @return - a {@link StoredDataResult} object
     * <p>
     * Note: mapper should never throws exception in case of using
     * {@link org.prebid.server.vertx.jdbc.CircuitBreakerSecuredJdbcClient}.
     */
    public StoredDataResult translateQueryResultToStoredData(ResultSet resultSet,
                                                             String accountId,
                                                             Set<String> requestIds,
                                                             Set<String> impIds) {

        final Map<String, String> storedIdToRequest;
        final Map<String, String> storedIdToImp;
        final List<String> errors = new ArrayList<>();

        if (resultSet == null || CollectionUtils.isEmpty(resultSet.getResults())) {
            storedIdToRequest = Collections.emptyMap();
            storedIdToImp = Collections.emptyMap();

            if (requestIds.isEmpty() && impIds.isEmpty()) {
                errors.add("No stored requests or imps were found");
            } else {
                final String errorRequests = requestIds.isEmpty() ? ""
                        : String.format("stored requests for ids %s", requestIds);
                final String separator = requestIds.isEmpty() || impIds.isEmpty() ? "" : " and ";
                final String errorImps = impIds.isEmpty() ? "" : String.format("stored imps for ids %s", impIds);

                errors.add(String.format("No %s%s%s were found", errorRequests, separator, errorImps));
            }
        } else {
            final Map<String, Set<StoredItem>> requestIdToStoredItems = new HashMap<>();
            final Map<String, Set<StoredItem>> impIdToStoredItems = new HashMap<>();

            for (JsonArray result : resultSet.getResults()) {
                final String fetchedAccountId;
                final String id;
                final String data;
                final String typeAsString;
                try {
                    fetchedAccountId = result.getString(0);
                    id = result.getString(1);
                    data = result.getString(2);
                    typeAsString = result.getString(3);
                } catch (IndexOutOfBoundsException | ClassCastException e) {
                    final String message = "Error occurred while mapping stored request data";
                    logger.error(message, e);
                    errors.add(message);
                    return StoredDataResult.of(Collections.emptyMap(), Collections.emptyMap(), errors);
                }

                final StoredDataType type;
                try {
                    type = StoredDataType.valueOf(typeAsString);
                } catch (IllegalArgumentException e) {
                    logger.error("Stored request data with id={0} has invalid type: ''{1}'' and will be ignored.", e,
                            id, typeAsString);
                    continue;
                }

                if (type == StoredDataType.request) {
                    addStoredItem(fetchedAccountId, id, data, requestIdToStoredItems);
                } else {
                    addStoredItem(fetchedAccountId, id, data, impIdToStoredItems);
                }
            }

            storedIdToRequest = storedItemsOrAddError(StoredDataType.request, accountId, requestIds,
                    requestIdToStoredItems, errors);
            storedIdToImp = storedItemsOrAddError(StoredDataType.imp, accountId, impIds,
                    impIdToStoredItems, errors);
        }

        return StoredDataResult.of(storedIdToRequest, storedIdToImp, errors);
    }

    /**
     * Overloaded method for cases when no specific IDs are required, e.g. fetching all records.
     *
     * @param resultSet - incoming {@link ResultSet} representing a result of SQL query.
     * @return - a {@link StoredDataResult} object.
     */
    public StoredDataResult translateQueryResultToStoredData(ResultSet resultSet) {
        return translateQueryResultToStoredData(resultSet, null, Collections.emptySet(), Collections.emptySet());
    }

    private static SqlQuery createStoredRequestQuery(String query, Set<String> requestIds, Set<String> impIds) {
        final List<Object> idsQueryParameters = new ArrayList<>();
        IntStream.rangeClosed(1, StringUtils.countMatches(query, REQUEST_ID_PLACEHOLDER))
                .forEach(i -> idsQueryParameters.addAll(requestIds));
        IntStream.rangeClosed(1, StringUtils.countMatches(query, IMP_ID_PLACEHOLDER))
                .forEach(i -> idsQueryParameters.addAll(impIds));

        final String parametrizedQuery = query
                .replace(REQUEST_ID_PLACEHOLDER, parameterHolders(requestIds.size()))
                .replace(IMP_ID_PLACEHOLDER, parameterHolders(impIds.size()));

        return SqlQuery.of(parametrizedQuery, idsQueryParameters);
    }

    private static SqlQuery createStoredResponseQuery(String query, Set<String> responseIds) {
        final List<Object> idsQueryParameters = new ArrayList<>();
        IntStream.rangeClosed(1, StringUtils.countMatches(query, RESPONSE_ID_PLACEHOLDER))
                .forEach(i -> idsQueryParameters.addAll(responseIds));

        final String parameterizedQuery = query
                .replace(RESPONSE_ID_PLACEHOLDER, parameterHolders(responseIds.size()));

        return SqlQuery.of(parameterizedQuery, idsQueryParameters);
    }

    /**
     * Returns string for parametrized placeholder.
     */
    private static String parameterHolders(int paramsSize) {
        return paramsSize == 0
                ? "NULL"
                : IntStream.range(0, paramsSize)
                .mapToObj(i -> QUERY_PARAM_PLACEHOLDER)
                .collect(Collectors.joining(","));
    }

    private static void addStoredItem(String accountId,
                                      String id,
                                      String data,
                                      Map<String, Set<StoredItem>> idToStoredItems) {

        final StoredItem storedItem = StoredItem.of(accountId, data);

        final Set<StoredItem> storedItems = idToStoredItems.get(id);
        if (storedItems == null) {
            idToStoredItems.put(id, new HashSet<>(Collections.singleton(storedItem)));
        } else {
            storedItems.add(storedItem);
        }
    }

    /**
     * Returns map of stored ID -> value or populates error.
     */
    private static Map<String, String> storedItemsOrAddError(StoredDataType type,
                                                             String accountId,
                                                             Set<String> searchIds,
                                                             Map<String, Set<StoredItem>> foundIdToStoredItems,
                                                             List<String> errors) {

        final Map<String, String> result = new HashMap<>();

        if (searchIds.isEmpty()) {
            for (Map.Entry<String, Set<StoredItem>> entry : foundIdToStoredItems.entrySet()) {
                entry.getValue().forEach(storedItem -> result.put(entry.getKey(), storedItem.getData()));
            }
        } else {
            for (String id : searchIds) {
                try {
                    final StoredItem resolvedStoredItem = StoredItemResolver.resolve(type, accountId, id,
                            foundIdToStoredItems.get(id));
                    result.put(id, resolvedStoredItem.getData());
                } catch (PreBidException e) {
                    errors.add(e.getMessage());
                }
            }
        }

        return result;
    }
}
