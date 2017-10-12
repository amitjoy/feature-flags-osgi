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

import static com.amitinside.featureflags.internal.Config.ENABLED;
import static java.util.Objects.requireNonNull;
import static org.osgi.framework.Constants.*;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amitinside.featureflags.FeatureService;
import com.amitinside.featureflags.feature.Feature;
import com.amitinside.featureflags.feature.group.FeatureGroup;
import com.amitinside.featureflags.strategy.ActivationStrategy;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;

/**
 * This service implements the {@link FeatureService}. It keeps track of all
 * {@link Feature} services and {@link ActivationStrategy} services.
 */
@Component(name = "FeatureManager", immediate = true)
public final class FeatureManager implements FeatureService {

    /** Logger Instance */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final Multimap<String, Description<Feature>> allFeatures = TreeMultimap.create();
    private final Multimap<String, Description<ActivationStrategy>> allStrategies = TreeMultimap.create();
    private final Multimap<String, Description<FeatureGroup>> allFeatureGroups = TreeMultimap.create();

    private final Map<String, Feature> activeFeatures = Maps.newHashMap();
    private final Map<String, ActivationStrategy> activeStrategies = Maps.newHashMap();
    private final Map<String, FeatureGroup> activeFeatureGroups = Maps.newHashMap();

    private final Lock featuresLock = new ReentrantLock(true);
    private final Lock strategiesLock = new ReentrantLock(true);
    private final Lock featureGroupsLock = new ReentrantLock(true);

    private ConfigurationAdmin configurationAdmin;

    @Override
    public Stream<Feature> getFeatures() {
        return activeFeatures.values().stream();
    }

    @Override
    public Stream<ActivationStrategy> getStrategies() {
        return activeStrategies.values().stream();
    }

    @Override
    public Stream<FeatureGroup> getGroups() {
        return activeFeatureGroups.values().stream();
    }

    @Override
    public Optional<Feature> getFeature(final String featureName) {
        requireNonNull(featureName, "Feature name cannot be null");
        return Optional.ofNullable(activeFeatures.get(featureName));
    }

    @Override
    public Optional<ActivationStrategy> getStrategy(final String strategyName) {
        requireNonNull(strategyName, "Strategy name cannot be null");
        return Optional.ofNullable(activeStrategies.get(strategyName));
    }

    @Override
    public Optional<FeatureGroup> getGroup(final String groupName) {
        requireNonNull(groupName, "Group name cannot be null");
        return Optional.ofNullable(activeFeatureGroups.get(groupName));
    }

    @Override
    public boolean isFeatureEnabled(final String featureName) {
        requireNonNull(featureName, "Feature name cannot be null");
        final Feature feature = getFeature(featureName).orElse(null);
        return feature != null ? checkEnablement(feature) : false;
    }

    @Override
    public boolean isGroupEnabled(final String groupName) {
        requireNonNull(groupName, "Group name cannot be null");
        final FeatureGroup group = getGroup(groupName).orElse(null);
        return group != null ? checkGroupEnablement(group) : false;
    }

    @Override
    public boolean enableFeature(final String featureName) {
        requireNonNull(featureName, "Feature name cannot be null");
        return toggleFeature(featureName, true);
    }

    @Override
    public boolean disableFeature(final String featureName) {
        requireNonNull(featureName, "Feature name cannot be null");
        return toggleFeature(featureName, false);
    }

    @Override
    public boolean enableGroup(final String groupName) {
        requireNonNull(groupName, "Feature Group name cannot be null");
        return toggleFeatureGroup(groupName, true);
    }

    @Override
    public boolean disableGroup(final String groupName) {
        requireNonNull(groupName, "Feature Group name cannot be null");
        return toggleFeatureGroup(groupName, false);
    }

    private boolean toggleFeature(final String featureName, final boolean status) {
        //@formatter:off
        final String pid = allFeatures.values().stream()
                .sorted()
                .filter(x -> x.instance.getName().equalsIgnoreCase(featureName))
                .findFirst()
                .map(f -> f.props)
                .map(m -> m.get(SERVICE_PID))
                .map(String.class::cast)
                .orElse("");
        //@formatter:on
        return pid.isEmpty() ? false : checkAndUpdateConfiguration(featureName, pid, status);
    }

    private boolean toggleFeatureGroup(final String groupName, final boolean status) {
        //@formatter:off
        final String pid = allFeatureGroups.values().stream()
                .sorted()
                .filter(x -> x.instance.getName().equalsIgnoreCase(groupName))
                .findFirst()
                .map(f -> f.props)
                .map(m -> m.get(SERVICE_PID))
                .map(String.class::cast)
                .orElse("");
         //@formatter:on
        return pid.isEmpty() ? false : checkAndUpdateConfiguration(groupName, pid, status);
    }

    private boolean checkAndUpdateConfiguration(final String name, final String pid, final boolean status) {
        try {
            final Configuration configuration = configurationAdmin.getConfiguration(pid, "?");
            final Map<String, Object> newProps = Maps.newHashMap();
            newProps.put(ENABLED.value(), status);
            configuration.update(new Hashtable<>(newProps));
            return true;
        } catch (final IOException e) {
            logger.trace("Cannot retrieve configuration for {}", name, e);
        }
        return false;
    }

    private boolean checkEnablement(final Feature feature) {
        final String groupId = feature.getGroup().orElse("");
        if (!groupId.isEmpty()) {
            final FeatureGroup group = getGroup(groupId).orElse(null);
            return checkGroupEnablement(group);
        }
        return checkFeatureStrategyEnablement(feature);
    }

    private boolean checkGroupEnablement(final FeatureGroup group) {
        final String strategyId = group.getStrategy().orElse("");
        if (!strategyId.isEmpty()) {
            final ActivationStrategy strategy = getStrategy(strategyId).orElse(null);
            if (strategy != null) {
                //@formatter:off
                    final Map<String, Object> properties = allFeatureGroups.values().stream()
                            .sorted()
                            .filter(x -> x.instance == group)
                            .findFirst()
                            .map(f -> f.props)
                            .orElse(ImmutableMap.of());
                    //@formatter:on
                return strategy.isEnabled(group, properties);
            }
        } else {
            return group.isEnabled();
        }
        return false;
    }

    private boolean checkFeatureStrategyEnablement(final Feature feature) {
        final String strategyId = feature.getStrategy().orElse("");
        if (!strategyId.isEmpty()) {
            final ActivationStrategy strategy = getStrategy(strategyId).orElse(null);
            if (strategy != null) {
                //@formatter:off
                final Map<String, Object> properties = allFeatures.values().stream()
                        .sorted()
                        .filter(x -> x.instance == feature)
                        .findFirst()
                        .map(f -> f.props)
                        .orElse(ImmutableMap.of());
                //@formatter:on
                return strategy.isEnabled(feature, properties);
            }
        }
        return feature.isEnabled();
    }

    /**
     * {@link ConfigurationAdmin} service binding callback
     */
    @Reference
    protected void setConfigurationAdmin(final ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    /**
     * {@link ConfigurationAdmin} service unbinding callback
     */
    protected void unsetConfigurationAdmin(final ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = null;
    }

    /**
     * {@link Feature} service binding callback
     */
    @Reference(cardinality = MULTIPLE, policy = DYNAMIC)
    protected void bindFeature(final Feature feature, final Map<String, Object> props) {
        featuresLock.lock();
        try {
            final String name = feature.getName();
            // ignore if null or empty
            if (Strings.isNullOrEmpty(name)) {
                return;
            }
            bindInstance(feature, name, props, allFeatures, activeFeatures);
        } finally {
            featuresLock.unlock();
        }
    }

    /**
     * {@link Feature} service unbinding callback
     */
    protected void unbindFeature(final Feature feature, final Map<String, Object> props) {
        featuresLock.lock();
        try {
            final String name = feature.getName();
            unbindInstance(feature, name, props, allFeatures, activeFeatures);
        } finally {
            featuresLock.unlock();
        }
    }

    /**
     * {@link ActivationStrategy} service binding callback
     */
    @Reference(cardinality = MULTIPLE, policy = DYNAMIC)
    protected void bindStrategy(final ActivationStrategy strategy, final Map<String, Object> props) {
        strategiesLock.lock();
        try {
            final String name = strategy.getName();
            // ignore if null or empty
            if (Strings.isNullOrEmpty(name)) {
                return;
            }
            bindInstance(strategy, name, props, allStrategies, activeStrategies);
        } finally {
            strategiesLock.unlock();
        }
    }

    /**
     * {@link ActivationStrategy} service unbinding callback
     */
    protected void unbindStrategy(final ActivationStrategy strategy, final Map<String, Object> props) {
        strategiesLock.lock();
        try {
            final String name = strategy.getName();
            unbindInstance(strategy, name, props, allStrategies, activeStrategies);
        } finally {
            strategiesLock.unlock();
        }
    }

    /**
     * {@link FeatureGroup} service binding callback
     */
    @Reference(cardinality = MULTIPLE, policy = DYNAMIC)
    protected void bindFeatrueGroup(final FeatureGroup group, final Map<String, Object> props) {
        featureGroupsLock.lock();
        try {
            final String name = group.getName();
            // ignore if null or empty
            if (Strings.isNullOrEmpty(name)) {
                return;
            }
            bindInstance(group, name, props, allFeatureGroups, activeFeatureGroups);
        } finally {
            featureGroupsLock.unlock();
        }
    }

    /**
     * {@link FeatureGroup} service unbinding callback
     */
    protected void unbindFeatrueGroup(final FeatureGroup group, final Map<String, Object> props) {
        featureGroupsLock.lock();
        try {
            final String name = group.getName();
            unbindInstance(group, name, props, allFeatureGroups, activeFeatureGroups);
        } finally {
            featureGroupsLock.unlock();
        }
    }

    private <T> void bindInstance(final T instance, final String name, final Map<String, Object> props,
            final Multimap<String, Description<T>> allInstances, final Map<String, T> activeInstances) {
        final Description<T> info = new Description<>(instance, props);
        allInstances.put(name.toLowerCase(), info);
        calculateActiveInstances(allInstances, activeInstances);
    }

    private <T> void unbindInstance(final T instance, final String name, final Map<String, Object> props,
            final Multimap<String, Description<T>> allInstances, final Map<String, T> activeInstances) {
        final Description<T> info = new Description<>(instance, props);
        allInstances.remove(name, info);
        calculateActiveInstances(allInstances, activeInstances);
    }

    /**
     * Calculates map of active elements (Strategy or Feature) (eliminating name
     * collisions)
     */
    private <T> void calculateActiveInstances(final Multimap<String, Description<T>> allElements,
            final Map<String, T> activeInstance) {
        final Map<String, T> activeMap = Maps.newHashMap();
        for (final Entry<String, Description<T>> entry : allElements.entries()) {
            final String key = entry.getKey();
            final SortedSet<Description<T>> value = (SortedSet<Description<T>>) allElements.get(key);
            final T instance = value.first().instance;
            activeMap.put(key, instance);
            if (value.size() > 1) {
                logger.warn("More than one " + instance.getClass().getSimpleName()
                        + " services with same name - [{}] are available.", key);
            }
        }
        activeInstance.clear();
        activeInstance.putAll(activeMap);
    }

    /**
     * Internal class caching some feature or strategy meta data like service ID and
     * ranking.
     */
    private static final class Description<T> implements Comparable<Description<T>> {
        private final int ranking;
        private final long serviceId;
        private final T instance;
        private final Map<String, Object> props;

        public Description(final T instance, final Map<String, Object> props) {
            this.instance = instance;
            this.props = ImmutableMap.copyOf(props);
            final Object sr = props.get(SERVICE_RANKING);
            ranking = Optional.ofNullable(sr).filter(e -> e instanceof Integer).map(Integer.class::cast).orElse(0);
            serviceId = (long) props.get(SERVICE_ID);
        }

        /**
         * First sort by highest service rankings. If service rankings are equal,
         * then sort by service ID in descending order.
         */
        @Override
        public int compareTo(final Description<T> o) {
            return ComparisonChain.start().compare(o.ranking, ranking).compare(serviceId, o.serviceId).result();
        }

        @Override
        public boolean equals(final Object obj) {
            return Objects.equals(serviceId, obj);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(serviceId);
        }
    }
}