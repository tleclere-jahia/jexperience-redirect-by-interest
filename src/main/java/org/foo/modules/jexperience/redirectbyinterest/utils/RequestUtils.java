package org.foo.modules.jexperience.redirectbyinterest.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationModule;
import org.apache.commons.lang.StringUtils;
import org.apache.taglibs.standard.tag.common.core.Util;
import org.jahia.api.Constants;
import org.jahia.api.content.JCRTemplate;
import org.jahia.exceptions.JahiaException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.preferences.user.UserPreferencesHelper;
import org.jahia.services.sites.JahiaSite;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.settings.SettingsBean;
import org.jahia.utils.LanguageCodeConverters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class RequestUtils {
    private RequestUtils() {
        // Constructor disabled for utility class
    }

    private static ObjectMapper objectMapper = null;

    private static final Logger logger = LoggerFactory.getLogger(RequestUtils.class);

    public static String getSiteKey(HttpServletRequest httpServletRequest, JahiaSitesService jahiaSitesService) {
        String siteKey;
        try {
            JahiaSite jahiaSite = jahiaSitesService.getSiteByServerName(httpServletRequest.getServerName());
            if (jahiaSite != null) {
                siteKey = jahiaSite.getSiteKey();
            } else {
                siteKey = jahiaSitesService.getDefaultSite().getSiteKey();
            }
        } catch (JahiaException e) {
            siteKey = jahiaSitesService.getDefaultSite().getSiteKey();
        }
        return siteKey;
    }

    private static boolean isLocaleSupported(JCRSiteNode site, List<Locale> siteLanguages, Locale locale) {
        return (site != null && site.isAllowsUnlistedLanguages()) || siteLanguages == null || siteLanguages.contains(locale) && ensureHomePageExists(site, locale);
    }

    private static boolean ensureHomePageExists(final JCRSiteNode site, final Locale curLocale) {
        try {
            return BundleUtils.getOsgiService(JCRTemplate.class, null).doExecuteWithSystemSessionAsUser(null, Constants.LIVE_WORKSPACE, curLocale, session -> {
                try {
                    JCRSiteNode nodeByIdentifier = (JCRSiteNode) session.getNodeByIdentifier(site
                            .getIdentifier());
                    return nodeByIdentifier.getHome() != null;
                } catch (RepositoryException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("This site does not have a published home in language " + curLocale, e);
                    }
                }
                return Boolean.FALSE;
            });
        } catch (RepositoryException e) {
            logger.error(e.getMessage(), e);
        }
        return false;
    }

    public static String resolveLanguage(HttpServletRequest request, final JCRSiteNode site, JCRUserNode user, boolean userRedirect) {
        List<Locale> siteLanguages = null;
        if (!userRedirect && site != null && !JahiaSitesService.SYSTEM_SITE_KEY.equals(site.getSiteKey())) {
            try {
                siteLanguages = site.getActiveLiveLanguagesAsLocales();
            } catch (Exception t) {
                logger.debug("Exception while getting language settings as locales", t);
                siteLanguages = Collections.emptyList();
            }
        }

        // first we will check the preferred user locale (if it is among the
        Locale preferredLocale = UserPreferencesHelper.getPreferredLocale(user);
        if (preferredLocale != null && isLocaleSupported(site, siteLanguages, preferredLocale)) {
            return preferredLocale.toString();
        }

        // retrieve the browser locales, but if Accept-Language header is missing we won't fallback to the default system locale
        for (Enumeration<?> requestLocales = Util.getRequestLocales(request); requestLocales.hasMoreElements(); ) {
            final Locale curLocale = (Locale) requestLocales.nextElement();
            if (curLocale != null) {
                // check that the site contains the language and the home page exists in live for that language
                if (isLocaleSupported(site, siteLanguages, curLocale)) {
                    return curLocale.toString();
                }
                if (!StringUtils.isEmpty(curLocale.getCountry())) {
                    // check the same but for language only
                    final Locale langOnlyLocale = LanguageCodeConverters.languageCodeToLocale(curLocale.getLanguage());
                    if (isLocaleSupported(site, siteLanguages, langOnlyLocale)) {
                        return langOnlyLocale.toString();
                    }
                }
            }
        }

        if (site != null) {
            String lang = site.getDefaultLanguage();
            if (lang != null) {
                // use site's default language
                return lang;
            }
        }

        // nothing matches -> fallback to default
        return StringUtils.defaultIfEmpty(SettingsBean.getInstance().getDefaultLanguageCode(), Locale.ENGLISH.toString());
    }

    public static ObjectMapper getObjectMapper() {
        if (objectMapper != null) {
            return objectMapper;
        }

        JaxbAnnotationModule module = new JaxbAnnotationModule();
        objectMapper = new ObjectMapper();
        // configure as necessary
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        objectMapper.registerModule(module);
        return objectMapper;
    }
}
