/*******************************************************************************
 * Copyright (c) 2017 Amit Kumar Mondal
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Amit Kumar Mondal
 *
 *******************************************************************************/
package com.amitinside.featureflags.internal;

import static com.amitinside.featureflags.Constants.PID;
import static com.amitinside.featureflags.internal.Config.*;
import static com.google.common.base.Charsets.UTF_8;
import static org.osgi.framework.Bundle.ACTIVE;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;

/**
 * {@link FeatureBootstrapper} is used to track all installed bundles. It looks for
 * {@code features.json} in the bundle and if found, it registers the specified features
 * as services whose factory PID is {@code com.amitinside.featureflags.feature}.
 * <br/>
 * <br/>
 * The features are specified in the resource file in the following way:
 *
 * <pre>
 * [
 *   {
 *       "name": "feature1",
 *       "description": "My Feature 1",
 *       "enabled": false
 *   },
 *   {
 *       "name": "feature2",
 *       "description": "My Feature 2",
 *       "strategy": "MyStrategy1"
 *   },
 *   {
 *       "name": "feature3",
 *       "description": "My Feature 3",
 *       "enabled": false,
 *       "strategy": "MyStrategy2"
 *   },
 *   {
 *       "name": "feature3",
 *       "description": "My Feature 3",
 *       "enabled": false,
 *       "strategy": "MyStrategy2",
 *       "properties": {
 *           "p1": 1,
 *           "p2": "test",
 *           "p3": [1, 2, 3, 4]
 *       }
 *   }
 * ]
 * </pre>
 *
 * <b>N.B:</b> You can also add extra properties to your feature as shown in the above example.
 * These properties will be added as your feature's service properties.
 */
@SuppressWarnings({ "rawtypes", "unused", "unchecked" })
@Component(service = FeatureBootstrapper.class, name = "FeatureBootstrapper")
public final class FeatureBootstrapper implements BundleTrackerCustomizer {

    /** Logger Instance */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private BundleTracker bundleTracker;
    private ConfigurationAdmin configurationAdmin;
    private final Gson gson = new Gson();

    // configuration PIDs associated with the bundle instance that contains the features
    private final Multimap<Bundle, String> allFeatures = ArrayListMultimap.create();

    @Activate
    private void activate(final BundleContext context) {
        bundleTracker = new BundleTracker(context, ACTIVE, this);
        bundleTracker.open();
    }

    @Deactivate
    private void deactivate(final BundleContext context) {
        if (bundleTracker != null) {
            bundleTracker.close();
        }
    }

    /**
     * {@link ConfigurationAdmin} service binding callback
     */
    @Reference
    private void setConfigurationAdmin(final ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    /**
     * {@link ConfigurationAdmin} service unbinding callback
     */
    private void unsetConfigurationAdmin(final ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = null;
    }

    @Override
    public Object addingBundle(final Bundle bundle, final BundleEvent event) {
        final List<Feature> features = getFeatures(bundle);
        for (final Feature feature : features) {
            registerFeature(feature).ifPresent(x -> {
                final Collection<String> registeredPids = allFeatures.get(bundle);
                registeredPids.add(x);
            });
        }
        return bundle;
    }

    @Override
    public void modifiedBundle(final Bundle bundle, final BundleEvent event, final Object object) {
        removedBundle(bundle, event, object);
        addingBundle(bundle, event);
    }

    @Override
    public void removedBundle(final Bundle bundle, final BundleEvent event, final Object object) {
        final Collection<String> features = allFeatures.get(bundle);
        if (features.isEmpty()) {
            return;
        }
        for (final String pid : features) {
            try {
                final Configuration config = configurationAdmin.getConfiguration(pid);
                config.delete();
            } catch (final IOException e) {
                logger.trace("Cannot delete feature configuration instance", e);
            }
        }
        allFeatures.removeAll(bundle);
    }

    /**
     * Registers the specified feature properties as a configurable
     * {@link com.amitinside.featureflags.feature.Feature} service
     *
     * @return the registered service PID wrapped in {@link Optional}
     *         instance or empty {@link Optional}
     */
    private Optional<String> registerFeature(final Feature feature) {
        try {
            final Map<String, Object> props = Maps.newHashMap();
            props.put(NAME.value(), feature.getName());
            props.put(DESCRIPTION.value(), feature.getDescription());
            props.put(STRATEGY.value(), feature.getStrategy());
            props.put(ENABLED.value(), Optional.ofNullable(feature.isEnabled()).orElse(false));
            final Map<String, Object> properties = feature.getProperties();
            if (properties != null) {
                props.putAll(properties);
            }
            // remove all null values
            Maps.filterValues(props, Objects::nonNull);
            final Configuration configuration = configurationAdmin.createFactoryConfiguration(PID);
            configuration.update(new Hashtable<>(props));
            return Optional.of(configuration.getPid());
        } catch (final IOException e) {
            logger.trace("Cannot create feature configuration instance", e);
        }
        return Optional.empty();
    }

    /**
     * Retrieves all the features specified in the bundle's {@code feature.json}
     * resource
     *
     * @param bundle the bundle to look into
     * @return the list of all specified features or empty list
     */
    private List<Feature> getFeatures(final Bundle bundle) {
        final URL featuresFileURL = bundle.getEntry("/features.json");
        if (featuresFileURL != null) {
            try (final InputStream inputStream = featuresFileURL.openConnection().getInputStream()) {
                final String resource = CharStreams.toString(new InputStreamReader(inputStream, UTF_8));
                return Lists.newArrayList(gson.fromJson(resource, Feature[].class));
            } catch (final IOException e) {
                logger.trace("Cannot retrieve feature JSON resource", e);
            }
        }
        return Collections.emptyList();
    }

    /**
     * Internal class used to represent JSON data
     */
    protected static final class Feature {
        private final String name;
        private final String description;
        private final String strategy;
        private final boolean isEnabled;
        private final Map<String, Object> properties;

        public Feature(final String name, final String description, final String strategy, final boolean isEnabled,
                final Map<String, Object> properties) {
            this.name = name;
            this.description = description;
            this.strategy = strategy;
            this.isEnabled = isEnabled;
            this.properties = properties;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getStrategy() {
            return strategy;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }

        public boolean isEnabled() {
            return isEnabled;
        }
    }

}