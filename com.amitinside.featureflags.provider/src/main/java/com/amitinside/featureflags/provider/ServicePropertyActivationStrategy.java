/*******************************************************************************
 * Copyright (c) 2017 Amit Kumar Mondal
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package com.amitinside.featureflags.provider;

import static com.amitinside.featureflags.Constants.STRATEGY_SERVICE_PROPERTY_PID;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;

import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import com.amitinside.featureflags.Strategizable;
import com.amitinside.featureflags.feature.Feature;
import com.amitinside.featureflags.feature.group.FeatureGroup;
import com.amitinside.featureflags.provider.ServicePropertyActivationStrategy.ServicePropertyStrategyConfig;
import com.amitinside.featureflags.strategy.ActivationStrategy;

/**
 * This strategy is responsible for checking configured property key and value in the
 * {@link Feature} or {@link FeatureGroup}'s OSGi service property.
 */
@Designate(ocd = ServicePropertyStrategyConfig.class, factory = true)
@Component(name = "ConfiguredServicePropertyStrategy", immediate = true, configurationPolicy = REQUIRE, configurationPid = STRATEGY_SERVICE_PROPERTY_PID, service = ActivationStrategy.class)
public final class ServicePropertyActivationStrategy extends AbstractPropertyActivationStrategy {

    @Override
    @Activate
    protected void activate(final Map<String, Object> properties) {
        super.activate(properties);
    }

    @Override
    @Modified
    protected void updated(final Map<String, Object> properties) {
        super.updated(properties);
    }

    @Override
    public boolean isEnabled(final Strategizable strategizable, final Map<String, Object> properties) {
        final String key = getKey().orElse(null);
        final String value = getValue().orElse(null);

        if (key == null || value == null) {
            return false;
        }
        for (final Entry<String, Object> entry : properties.entrySet()) {
            final String k = entry.getKey();
            final String v = String.valueOf(entry.getValue());
            if (Pattern.matches(key, k) && Pattern.matches(value, v)) {
                return true;
            }
        }
        return false;
    }

    @ObjectClassDefinition(id = STRATEGY_SERVICE_PROPERTY_PID, name = "Service Property Activation Strategy", description = "Allows for the definition of statically configured service property strategy which are defined and enabled through OSGi configuration")
    @interface ServicePropertyStrategyConfig {

        @AttributeDefinition(description = "Short friendly name of this strategy")
        String name() default "MyStrategy";

        @AttributeDefinition(description = "Description of this strategy")
        String description() default "MyStrategyDescription";

        @AttributeDefinition(name = "property_key", description = "Property Key to check in the associated feature or feature group's service properties")
        String propertyKey() default "";

        @AttributeDefinition(name = "property_value", description = "Property Value to check in the associated feature or feature group's service properties")
        String propertyValue() default "";
    }

}