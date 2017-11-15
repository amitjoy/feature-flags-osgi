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

import static com.amitinside.featureflags.FeatureManager.FEATURE_NAME_PREFIX;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.osgi.service.metatype.ObjectClassDefinition.ALL;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.service.metatype.AttributeDefinition;
import org.osgi.service.metatype.MetaTypeInformation;
import org.osgi.service.metatype.MetaTypeService;
import org.osgi.util.tracker.BundleTrackerCustomizer;

import com.amitinside.featureflags.provider.FeatureManagerProvider.Feature;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;

/**
 * The {@link MetaTypeTrackerCustomizer} is used to track all existing metatype
 * informations in a bundle. It specifically tracks if the associated metatype
 * information does specify any feature.
 */
public final class MetaTypeTrackerCustomizer implements BundleTrackerCustomizer {

    /** Metatype Service Instance Reference */
    private final MetaTypeService metaTypeService;

    /** Data container -> Key: Bundle Instance Value: Configuration PID */
    private final Multimap<Bundle, String> bundlePids;

    /** Data container -> Key: Configuration PID Value: Feature DTOs */
    private final Multimap<String, Feature> allFeatures;

    /**
     * Constructor
     *
     * @param metaTypeService {@link MetaTypeService} instance
     * @param bundlePids container to store all configuration PIDs associated in a bundle's metatype
     * @param allFeatures container to store all configuration PIDs in the runtime
     *
     * @throws NullPointerException if any of the specified arguments is {@code null}
     */
    public MetaTypeTrackerCustomizer(final MetaTypeService metaTypeService, final Multimap<Bundle, String> bundlePids,
            final Multimap<String, Feature> allFeatures) {
        requireNonNull(metaTypeService, "MetaTypeService instance cannot be null");
        requireNonNull(bundlePids, "Multimap instance cannot be null");
        requireNonNull(allFeatures, "Multimap instance cannot be null");

        this.metaTypeService = metaTypeService;
        this.bundlePids = bundlePids;
        this.allFeatures = allFeatures;
    }

    @Override
    public Object addingBundle(final Bundle bundle, final BundleEvent event) {
        //@formatter:off
        //retrieve list of PIDs associated to Metatype in a bundle
        final List<String> pids = Optional.of(bundle)
                                          .map(metaTypeService::getMetaTypeInformation)
                                          .filter(Objects::nonNull)
                                          .map(MetaTypeInformation::getPids)
                                          .map(Arrays::stream)
                                          .map(s -> s.collect(toList()))
                                          .orElse(ImmutableList.of());

        pids.stream().forEach(pid -> bundlePids.put(bundle, pid));

        //retrieve list of specified attribute definitions
        for (final String pid : pids) {
            final List<AttributeDefinition> attributeDefinitions = getAttributeDefinitions(bundle, pid);
            attributeDefinitions.stream()
                                .filter(ad -> ad.getID().startsWith(FEATURE_NAME_PREFIX))
                                .map(ad -> toFeature(ad.getID(),
                                                     ad.getDescription(),
                                                     Boolean.valueOf(ad.getDefaultValue()[0])))
                                .forEach(f -> allFeatures.put(pid, f));
        }
        //@formatter:on
        return bundle;
    }

    @Override
    public void modifiedBundle(final Bundle bundle, final BundleEvent event, final Object object) {
        // not required
    }

    @Override
    public void removedBundle(final Bundle bundle, final BundleEvent event, final Object object) {
        if (bundlePids.containsKey(bundle)) {
            final Collection<String> pids = bundlePids.get(bundle);
            bundlePids.removeAll(bundle);
            pids.forEach(allFeatures::removeAll);
        }
    }

    private List<AttributeDefinition> getAttributeDefinitions(final Bundle bundle, final String pid) {
        //@formatter:off
        return Optional.ofNullable(bundle)
                       .map(metaTypeService::getMetaTypeInformation)
                       .filter(Objects::nonNull)
                       .map(m -> m.getObjectClassDefinition(pid, null))
                       .map(o -> o.getAttributeDefinitions(ALL))
                       .filter(Objects::nonNull)
                       .map(Arrays::stream)
                       .map(s -> s.collect(toList()))
                       .orElse(ImmutableList.of());
        //@formatter:on
    }

    private static Feature toFeature(final String name, final String description, final boolean isEnabled) {
        final Feature feature = new Feature();
        feature.name = extractName(name);
        feature.description = description;
        feature.isEnabled = isEnabled;
        return feature;
    }

    private static String extractName(final String name) {
        return name.substring(FEATURE_NAME_PREFIX.length(), name.length());
    }

}