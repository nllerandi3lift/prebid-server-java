package org.prebid.server.bidder.smaato;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.auction.model.Endpoint;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.bidder.smaato.proto.SmaatoBidRequestExt;
import org.prebid.server.bidder.smaato.proto.SmaatoImage;
import org.prebid.server.bidder.smaato.proto.SmaatoImageAd;
import org.prebid.server.bidder.smaato.proto.SmaatoImg;
import org.prebid.server.bidder.smaato.proto.SmaatoMediaData;
import org.prebid.server.bidder.smaato.proto.SmaatoRichMediaAd;
import org.prebid.server.bidder.smaato.proto.SmaatoRichmedia;
import org.prebid.server.bidder.smaato.proto.SmaatoSiteExtData;
import org.prebid.server.bidder.smaato.proto.SmaatoUserExtData;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebidPbs;
import org.prebid.server.proto.openrtb.ext.request.ExtSite;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;
import org.prebid.server.proto.openrtb.ext.request.smaato.ExtImpSmaato;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Smaato {@link Bidder} implementation.
 */
public class SmaatoBidder implements Bidder<BidRequest> {

    private static final TypeReference<ExtPrebid<?, ExtImpSmaato>> SMAATO_EXT_TYPE_REFERENCE =
            new TypeReference<ExtPrebid<?, ExtImpSmaato>>() {
            };

    private static final String CLIENT_VERSION = "prebid_server_0.4";
    private static final String SMT_ADTYPE_HEADER = "X-SMT-ADTYPE";
    private static final String SMT_AD_TYPE_IMG = "Img";
    private static final String SMT_ADTYPE_RICHMEDIA = "Richmedia";
    private static final String SMT_ADTYPE_VIDEO = "Video";

    private final String endpointUrl;
    private final JacksonMapper mapper;

    public SmaatoBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final BidRequest enrichedRequest;
        try {
            enrichedRequest = enrichRequestWithCommonProperties(request);
        } catch (PreBidException e) {
            return Result.withError(BidderError.badInput(e.getMessage()));
        }

        final List<BidderError> errors = new ArrayList<>();
        if (isVideoRequest(request)) {
            return Result.of(constructPodRequests(enrichedRequest, errors), errors);
        }
        return Result.of(constructIndividualRequests(enrichedRequest, errors), errors);
    }

    private BidRequest enrichRequestWithCommonProperties(BidRequest bidRequest) {
        final ExtRequest requestExt = ExtRequest.empty();
        requestExt.addProperty("client", TextNode.valueOf(CLIENT_VERSION));

        return bidRequest.toBuilder()
                .user(modifyUser(bidRequest.getUser()))
                .site(modifySite(bidRequest.getSite()))
                .ext(requestExt)
                .build();
    }

    private User modifyUser(User user) {
        final ExtUser userExt = user != null ? user.getExt() : null;
        if (userExt == null) {
            return user;
        }

        final ObjectNode extDataNode = userExt.getData();
        if (extDataNode == null || extDataNode.isEmpty()) {
            return user;
        }

        final SmaatoUserExtData smaatoUserExtData = convertExt(extDataNode, SmaatoUserExtData.class);
        final User.UserBuilder userBuilder = user.toBuilder();

        final String gender = smaatoUserExtData.getGender();
        if (StringUtils.isNotBlank(gender)) {
            userBuilder.gender(gender);
        }

        final Integer yob = smaatoUserExtData.getYob();
        if (yob != null && yob != 0) {
            userBuilder.yob(yob);
        }

        final String keywords = smaatoUserExtData.getKeywords();
        if (StringUtils.isNotBlank(keywords)) {
            userBuilder.keywords(keywords);
        }

        return userBuilder
                .ext(userExt.toBuilder().data(null).build())
                .build();
    }

    private Site modifySite(Site site) {
        if (site == null) {
            return null;
        }

        final ExtSite siteExt = site.getExt();
        if (siteExt != null) {
            final SmaatoSiteExtData data = convertExt(siteExt.getData(), SmaatoSiteExtData.class);
            final String keywords = data != null ? data.getKeywords() : null;
            return Site.builder().keywords(keywords).ext(null).build();
        }

        return site;
    }

    private <T> T convertExt(ObjectNode ext, Class<T> className) {
        try {
            return mapper.mapper().convertValue(ext, className);
        } catch (IllegalArgumentException e) {
            throw new PreBidException(String.format("Cannot decode extension: %s", e.getMessage()), e);
        }
    }

    private List<HttpRequest<BidRequest>> constructPodRequests(BidRequest bidRequest, List<BidderError> errors) {
        final List<Imp> validImps = new ArrayList<>();
        for (Imp imp : bidRequest.getImp()) {
            if (imp.getVideo() == null) {
                errors.add(BidderError.badInput("Invalid MediaType. Smaato only supports Video for AdPod."));
                continue;
            }
            validImps.add(imp);
        }

        return validImps.stream()
                .collect(Collectors.groupingBy(SmaatoBidder::extractPod, Collectors.toList()))
                .values().stream()
                .map(imps -> constructHttpRequest(preparePodRequest(bidRequest, imps)))
                .collect(Collectors.toList());
    }

    private static String extractPod(Imp imp) {
        return imp.getId().split("_")[0];
    }

    private BidRequest preparePodRequest(BidRequest bidRequest, List<Imp> imps) {
        return enrichWithPublisherId(bidRequest.toBuilder(), bidRequest, imps.get(0))
                .imp(modifyImpForAdBreak(imps))
                .build();
    }

    private List<HttpRequest<BidRequest>> constructIndividualRequests(BidRequest bidRequest, List<BidderError> errors) {
        final List<Imp> videoImps = new ArrayList<>();
        final List<Imp> bannerImps = new ArrayList<>();

        for (Imp imp : bidRequest.getImp()) {
            final Banner banner = imp.getBanner();
            final Video video = imp.getVideo();
            if (video == null && banner == null) {
                errors.add(BidderError.badInput("Invalid MediaType. Smaato only supports Banner and Video."));
                continue;
            }

            if (video != null) {
                videoImps.add(imp.toBuilder().video(null).build());
            }
            if (banner != null) {
                bannerImps.add(imp.toBuilder().banner(null).build());
            }
        }

        return Stream.of(videoImps, bannerImps)
                .flatMap(List::stream)
                .map(imp -> prepareIndividualRequest(bidRequest, imp))
                .map(this::constructHttpRequest)
                .collect(Collectors.toList());
    }

    private BidRequest prepareIndividualRequest(BidRequest bidRequest, Imp imp) {
        return enrichWithPublisherId(bidRequest.toBuilder(), bidRequest, imp)
                .imp(Collections.singletonList(modifyImpForAdspace(imp)))
                .build();
    }

    private BidRequest.BidRequestBuilder enrichWithPublisherId(BidRequest.BidRequestBuilder bidRequestBuilder,
                                                               BidRequest bidRequest,
                                                               Imp imp) {
        final Publisher publisher = getPublisher(imp);
        final Site site = bidRequest.getSite();
        final App app = bidRequest.getApp();
        if (site != null) {
            bidRequestBuilder.site(site.toBuilder().publisher(publisher).build());
        } else if (app != null) {
            bidRequestBuilder.app(app.toBuilder().publisher(publisher).build());
        } else {
            throw new PreBidException("Missing Site/App.");
        }

        return bidRequestBuilder;
    }

    private Publisher getPublisher(Imp imp) {
        final JsonNode publisherIdNode = imp.getExt().path("bidder").path("publisherId");
        if (publisherIdNode == null || !publisherIdNode.isTextual()) {
            throw new PreBidException("Missing publisherId parameter.");
        }

        return Publisher.builder().id(publisherIdNode.asText()).build();
    }

    private Imp modifyImpForAdspace(Imp imp) {
        final JsonNode adSpaceIdNode = imp.getExt().path("bidder").path("adspaceId");
        if (adSpaceIdNode == null || !adSpaceIdNode.isTextual()) {
            throw new PreBidException("Missing publisherId parameter.");
        }

        final Imp.ImpBuilder impBuilder = imp.toBuilder()
                .tagid(adSpaceIdNode.asText())
                .ext(null);

        final Banner banner = imp.getBanner();
        if (banner != null) {
            return impBuilder.banner(modifyBanner(banner)).build();
        } else if (imp.getVideo() != null) {
            return impBuilder.build();
        }

        return imp;
    }

    private List<Imp> modifyImpForAdBreak(List<Imp> imps) {
        return null;
    }


    private HttpRequest<BidRequest> constructHttpRequest(BidRequest bidRequest) {
        return HttpRequest.<BidRequest>builder()
                .uri(endpointUrl)
                .method(HttpMethod.POST)
                .headers(HttpUtil.headers())
                .body(mapper.encode(bidRequest))
                .payload(bidRequest)
                .build();
    }

    private ExtImpSmaato parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), SMAATO_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    private static Imp modifyImp(Imp imp, String adspaceId) {
        final Imp.ImpBuilder impBuilder = imp.toBuilder();
        if (imp.getBanner() != null) {
            return impBuilder.banner(modifyBanner(imp.getBanner())).tagid(adspaceId).ext(null).build();
        }

        if (imp.getVideo() != null) {
            return impBuilder.tagid(adspaceId).ext(null).build();
        }
        throw new PreBidException(String.format(
                "invalid MediaType. SMAATO only supports Banner and Video. Ignoring ImpID=%s", imp.getId()));
    }

    private static Banner modifyBanner(Banner banner) {
        if (banner.getW() != null && banner.getH() != null) {
            return banner;
        }
        final List<Format> format = banner.getFormat();
        if (CollectionUtils.isEmpty(format)) {
            throw new PreBidException(String.format("No sizes provided for Banner %s", format));
        }
        final Format firstFormat = format.get(0);
        return banner.toBuilder().w(firstFormat.getW()).h(firstFormat.getH()).build();
    }

    private static boolean isVideoRequest(BidRequest bidRequest) {
        final ExtRequest requestExt = bidRequest.getExt();
        final ExtRequestPrebid prebid = requestExt != null ? requestExt.getPrebid() : null;
        final ExtRequestPrebidPbs pbs = prebid != null ? prebid.getPbs() : null;
        final String endpointName = pbs != null ? pbs.getEndpoint() : null;

        return StringUtils.equals(endpointName, Endpoint.openrtb2_video.value());
    }

    private Result<List<HttpRequest<BidRequest>>> constructResult(BidRequest bidRequest,
                                                                  List<Imp> imps,
                                                                  String firstPublisherId,
                                                                  List<BidderError> errors) {
        final BidRequest outgoingRequest;
        try {
            outgoingRequest = modifyBidRequest(bidRequest, imps, firstPublisherId);
        } catch (PreBidException e) {
            errors.add(BidderError.badInput(e.getMessage()));
            return Result.withErrors(errors);
        }

        return Result.of(
                Collections.singletonList(
                        HttpRequest.<BidRequest>builder()
                                .method(HttpMethod.POST)
                                .uri(endpointUrl)
                                .headers(HttpUtil.headers())
                                .payload(outgoingRequest)
                                .body(mapper.encode(outgoingRequest))
                                .build()),
                errors);
    }

    private BidRequest modifyBidRequest(BidRequest bidRequest, List<Imp> imps, String firstPublisherId) {
        return bidRequest.toBuilder()
                .imp(imps)
                .site(modifySite(bidRequest.getSite(), firstPublisherId))
                .app(modifyApp(bidRequest.getApp(), firstPublisherId))
                .user(modifyUser(bidRequest.getUser()))
                .ext(mapper.fillExtension(ExtRequest.empty(), SmaatoBidRequestExt.of(CLIENT_VERSION)))
                .build();
    }

    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            return extractBids(bidResponse, httpCall.getResponse().getHeaders());
        } catch (DecodeException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        } catch (PreBidException e) {
            return Result.withError(BidderError.badInput(e.getMessage()));
        }
    }

    private Result<List<BidderBid>> extractBids(BidResponse bidResponse, MultiMap headers) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Result.empty();
        }

        return Result.withValues(bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> bidderBid(bid, bidResponse.getCur(), headers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private BidderBid bidderBid(Bid bid, String currency, MultiMap headers) {
        final String bidAdm = bid.getAdm();
        if (StringUtils.isBlank(bidAdm)) {
            throw new PreBidException(String.format("Empty ad markup in bid with id: %s", bid.getId()));
        }

        final String markupType = getAdMarkupType(headers, bidAdm);
        final Bid updateBid = bid.toBuilder().adm(renderAdMarkup(markupType, bidAdm)).build();
        return BidderBid.of(updateBid, getBidType(markupType), currency);
    }

    private static String getAdMarkupType(MultiMap headers, String adm) {
        final String adMarkupType = headers.get(SMT_ADTYPE_HEADER);
        if (StringUtils.isNotBlank(adMarkupType)) {
            return adMarkupType;
        }
        if (adm.startsWith("image")) {
            return SMT_AD_TYPE_IMG;
        }
        if (adm.startsWith("richmedia")) {
            return SMT_ADTYPE_RICHMEDIA;
        }
        if (adm.startsWith("<?xml")) {
            return SMT_ADTYPE_VIDEO;
        }
        throw new PreBidException(String.format("Invalid ad markup %s", adm));
    }

    private String renderAdMarkup(String markupType, String adm) {
        switch (markupType) {
            case SMT_AD_TYPE_IMG:
                return extractAdmImage(adm);
            case SMT_ADTYPE_RICHMEDIA:
                return extractAdmRichMedia(adm);
            case SMT_ADTYPE_VIDEO:
                return markupType;
            default:
                throw new PreBidException(String.format("Unknown markup type %s", markupType));
        }
    }

    private String extractAdmImage(String adm) {
        final SmaatoImageAd imageAd = convertAdmToAd(adm, SmaatoImageAd.class);
        final SmaatoImage image = imageAd.getImage();
        if (image == null) {
            throw new PreBidException("bid.adm.image is empty");
        }

        final StringBuilder clickEvent = new StringBuilder();
        CollectionUtils.emptyIfNull(image.getClickTrackers())
                .forEach(tracker -> clickEvent.append(String.format(
                        "fetch(decodeURIComponent('%s'.replace(/\\+/g, ' ')), {cache: 'no-cache'});",
                        HttpUtil.encodeUrl(StringUtils.stripToEmpty(tracker)))));

        final StringBuilder impressionTracker = new StringBuilder();
        CollectionUtils.emptyIfNull(image.getImpressionTrackers())
                .forEach(tracker -> impressionTracker.append(
                        String.format("<img src=\"%s\" alt=\"\" width=\"0\" height=\"0\"/>", tracker)));

        final SmaatoImg img = image.getImg();
        return String.format("<div style=\"cursor:pointer\" onclick=\"%s;window.open(decodeURIComponent"
                        + "('%s'.replace(/\\+/g, ' ')));\"><img src=\"%s\" width=\"%d\" height=\"%d\"/>%s</div>",
                clickEvent.toString(),
                HttpUtil.encodeUrl(StringUtils.stripToEmpty(getIfNotNull(img, SmaatoImg::getCtaurl))),
                StringUtils.stripToEmpty(getIfNotNull(img, SmaatoImg::getUrl)),
                stripToZero(getIfNotNull(img, SmaatoImg::getW)),
                stripToZero(getIfNotNull(img, SmaatoImg::getH)),
                impressionTracker.toString());
    }

    private String extractAdmRichMedia(String adm) {
        final SmaatoRichMediaAd richMediaAd = convertAdmToAd(adm, SmaatoRichMediaAd.class);
        final SmaatoRichmedia richmedia = richMediaAd.getRichmedia();
        if (richmedia == null) {
            throw new PreBidException("bid.adm.richmedia is empty");
        }

        final StringBuilder clickEvent = new StringBuilder();
        CollectionUtils.emptyIfNull(richmedia.getClickTrackers())
                .forEach(tracker -> clickEvent.append(
                        String.format("fetch(decodeURIComponent('%s'), {cache: 'no-cache'});",
                                HttpUtil.encodeUrl(StringUtils.stripToEmpty(tracker)))));

        final StringBuilder impressionTracker = new StringBuilder();
        CollectionUtils.emptyIfNull(richmedia.getImpressionTrackers())
                .forEach(tracker -> impressionTracker.append(
                        String.format("<img src=\"%s\" alt=\"\" width=\"0\" height=\"0\"/>", tracker)));

        return String.format("<div onclick=\"%s\">%s%s</div>",
                clickEvent.toString(),
                StringUtils.stripToEmpty(getIfNotNull(richmedia.getMediadata(), SmaatoMediaData::getContent)),
                impressionTracker.toString());
    }

    private <T> T convertAdmToAd(String value, Class<T> className) {
        try {
            return mapper.decodeValue(value, className);
        } catch (DecodeException e) {
            throw new PreBidException(String.format("Cannot decode bid.adm: %s", e.getMessage()), e);
        }
    }

    private static BidType getBidType(String markupType) {
        switch (markupType) {
            case SMT_AD_TYPE_IMG:
            case SMT_ADTYPE_RICHMEDIA:
                return BidType.banner;
            case SMT_ADTYPE_VIDEO:
                return BidType.video;
            default:
                throw new PreBidException(String.format("Invalid markupType %s", markupType));
        }
    }

    private static <T, R> R getIfNotNull(T target, Function<T, R> getter) {
        return target != null ? getter.apply(target) : null;
    }

    private static int stripToZero(Integer target) {
        return ObjectUtils.defaultIfNull(target, 0);
    }
}
