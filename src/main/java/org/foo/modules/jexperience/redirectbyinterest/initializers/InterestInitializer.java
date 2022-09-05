package org.foo.modules.jexperience.redirectbyinterest.initializers;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jahia.api.content.JCRTemplate;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.content.nodetypes.initializers.ModuleChoiceListInitializer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;
import java.util.*;
import java.util.stream.Collectors;

@Component(service = ModuleChoiceListInitializer.class)
public class InterestInitializer implements ModuleChoiceListInitializer {
    private static final Logger logger = LoggerFactory.getLogger(InterestInitializer.class);

    private JCRTemplate jcrTemplate;

    @Reference
    private void setJcrTemplate(JCRTemplate jcrTemplate) {
        this.jcrTemplate = jcrTemplate;
    }

    @Override
    public void setKey(String s) {
        // Nothing to do
    }

    @Override
    public String getKey() {
        return "interests";
    }

    @Override
    public List<ChoiceListValue> getChoiceListValues(ExtendedPropertyDefinition epd, String param, List<ChoiceListValue> values, Locale locale, Map<String, Object> context) {
        try {
            return jcrTemplate.doExecuteWithSystemSession(systemSession -> {
                JCRNodeIteratorWrapper it = systemSession.getWorkspace().getQueryManager().createQuery(
                        "SELECT * FROM [wemmix:wemInterests]", Query.JCR_SQL2).execute().getNodes();
                JCRNodeWrapper node;
                JCRPropertyWrapper interest;
                List<Value> propertyValues;
                Set<String> innerInterests = new HashSet<>();
                while (it.hasNext()) {
                    node = (JCRNodeWrapper) it.nextNode();
                    interest = node.getProperty("wem:interests");
                    if (interest != null) {
                        propertyValues = Arrays.asList(interest.getValues());
                        if (CollectionUtils.isNotEmpty(propertyValues)) {
                            for (Value propertyValue : propertyValues) {
                                innerInterests.add(StringUtils.substringBetween(propertyValue.getString(), "redirect-", ":"));
                            }
                        }
                    }
                }
                return innerInterests;
            }).stream().map(i -> new ChoiceListValue(i, i)).collect(Collectors.toList());
        } catch (RepositoryException e) {
            logger.error("", e);
            return Collections.emptyList();
        }
    }
}
