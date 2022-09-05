package org.foo.modules.jexperience.redirectbyinterest.filters;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.apache.commons.lang.StringUtils;
import org.apache.unomi.api.ContextRequest;
import org.apache.unomi.api.CustomItem;
import org.foo.modules.jexperience.redirectbyinterest.utils.RequestUtils;
import org.jahia.api.usermanager.JahiaUserManagerService;
import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.modules.jexperience.admin.ContextServerService;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.services.usermanager.JahiaUser;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import javax.servlet.*;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

@Component(service = AbstractServletFilter.class, immediate = true)
public class HomePageRedirectionFilter extends AbstractServletFilter {
    private static final Logger logger = LoggerFactory.getLogger(HomePageRedirectionFilter.class);

    private ContextServerService contextServerService;
    private JahiaSitesService jahiaSitesService;
    private JCRSessionFactory jcrSessionFactory;
    private JahiaUserManagerService jahiaUserManagerService;

    @Reference
    private void setContextServerService(ContextServerService contextServerService) {
        this.contextServerService = contextServerService;
    }

    @Reference
    private void setJahiaSitesService(JahiaSitesService jahiaSitesService) {
        this.jahiaSitesService = jahiaSitesService;
    }

    @Reference
    private void setJcrSessionFactory(JCRSessionFactory jcrSessionFactory) {
        this.jcrSessionFactory = jcrSessionFactory;
    }

    @Reference
    private void setJahiaUserManagerService(JahiaUserManagerService jahiaUserManagerService) {
        this.jahiaUserManagerService = jahiaUserManagerService;
    }

    public HomePageRedirectionFilter() {
        setUrlPatterns(new String[]{"/", "/home", "/home.html"});
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Do nothing
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
        String siteKey = RequestUtils.getSiteKey(httpServletRequest, jahiaSitesService);
        JahiaUser saveUser = jcrSessionFactory.getCurrentUser();
        try {
            jcrSessionFactory.setCurrentUser(jahiaUserManagerService.lookupRootUser().getJahiaUser());
            JCRSessionWrapper jcrSessionWrapper = jcrSessionFactory.getCurrentUserSession();
            JCRSiteNode siteNode = jahiaSitesService.getSiteByKey(siteKey, jcrSessionWrapper);
            String language = RequestUtils.resolveLanguage(httpServletRequest, siteNode, jcrSessionWrapper.getUserNode(), false);
            String interest = getProfileInterests(httpServletRequest, siteKey);
            String redirectionPath = siteNode.getHome().getPath();
            if (StringUtils.isEmpty(interest) && siteNode.isNodeType("foomix:redirectionOptions") && siteNode.hasProperty("defaultHomePage")) {
                redirectionPath = ((JCRNodeWrapper) siteNode.getProperty("defaultHomePage").getNode()).getPath();
            } else if (StringUtils.isNotEmpty(interest)) {
                JCRNodeIteratorWrapper it = jcrSessionWrapper.getWorkspace().getQueryManager().createQuery(
                        "SELECT * FROM [foomix:tagByInterest] WHERE " +
                                "ISDESCENDANTNODE('" + siteNode.getPath() + "') " +
                                "AND interests = '" + interest + "'", Query.JCR_SQL2).execute().getNodes();
                if (it.hasNext()) {
                    redirectionPath = ((JCRNodeWrapper) it.nextNode()).getPath();
                }
            }
            httpServletResponse.sendRedirect(httpServletResponse.encodeRedirectURL(
                    httpServletRequest.getContextPath() + "/cms/render/" + org.jahia.api.Constants.LIVE_WORKSPACE + "/" + language + redirectionPath + ".html"
            ));
        } catch (RepositoryException e) {
            logger.error("", e);
        } finally {
            jcrSessionFactory.setCurrentUser(saveUser);
        }

        filterChain.doFilter(servletRequest, servletResponse);
    }

    private String getProfileInterests(HttpServletRequest httpServletRequest, String siteKey) {
        try {
            Cookie[] cookies = httpServletRequest.getCookies();
            String profileId = null;
            if (cookies != null) {
                Optional<Cookie> cookie = Arrays.stream(cookies).filter(c -> "wem-profile-id".equals(c.getName())).findFirst();
                if (cookie.isPresent()) {
                    profileId = cookie.get().getValue();
                }
            }

            ContextRequest contextRequest = new ContextRequest();
            CustomItem source = new CustomItem("/sites/" + siteKey, "site");
            source.setScope(siteKey);
            contextRequest.setSource(source);
            contextRequest.setRequireSegments(false);
            contextRequest.setRequiredProfileProperties(Collections.singletonList("*"));

            final AsyncHttpClient asyncHttpClient = contextServerService.initAsyncHttpClient(siteKey);
            AsyncHttpClient.BoundRequestBuilder rb = contextServerService.initAsyncRequestBuilder(siteKey, asyncHttpClient, "/context.json?sessionId=" + contextServerService.getWemSessionId(httpServletRequest), false, false, true);
            rb.setHeader("Content-Type", "application/json");
            if (profileId != null) {
                rb.setHeader("Cookie", "context-profile-id=" + profileId);
            }

            JSONObject profileProperties = new JSONObject(rb.setBody(RequestUtils.getObjectMapper().writeValueAsString(contextRequest)).execute(new AsyncCompletionHandler<Response>() {
                @Override
                public void onThrowable(Throwable t) {
                    asyncHttpClient.closeAsynchronously();
                }

                @Override
                public Response onCompleted(Response response) {
                    asyncHttpClient.closeAsynchronously();
                    return response;
                }
            }).get().getResponseBody()).getJSONObject("profileProperties");
            if (!profileProperties.has("interests")) {
                return null;
            }
            JSONObject interestsJson = profileProperties.getJSONObject("interests");
            Iterator<String> it = interestsJson.keys();
            Map<String, Integer> interestsByWeight = new LinkedHashMap<>();
            String interest;
            while (it.hasNext()) {
                interest = it.next();
                interestsByWeight.put(interest, (Integer) interestsJson.get(interest));
            }

            Map<String, Integer> interestsByWeightSorted = new LinkedHashMap<>();
            interestsByWeight.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .forEachOrdered(x -> interestsByWeightSorted.put(x.getKey(), x.getValue()));
            return interestsByWeightSorted.keySet().stream().findFirst().orElse(null);
        } catch (IOException | InterruptedException | ExecutionException | JSONException e) {
            logger.error("", e);
        }
        return null;
    }

    @Override
    public void destroy() {
        // Do nothing
    }
}
