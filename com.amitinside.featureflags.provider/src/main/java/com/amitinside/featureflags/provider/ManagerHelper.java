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

import static com.amitinside.featureflags.FeatureManager.METATYPE_FEATURE_ID_PREFIX;
import static java.util.Objects.requireNonNull;
import static org.osgi.service.metatype.ObjectClassDefinition.ALL;

import java.util.List;

import org.osgi.framework.Bundle;
import org.osgi.service.metatype.AttributeDefinition;
import org.osgi.service.metatype.MetaTypeInformation;
import org.osgi.service.metatype.MetaTypeService;
import org.osgi.service.metatype.ObjectClassDefinition;

import com.amitinside.featureflags.dto.FeatureDTO;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;

/**
 * Feature Manager Helper class
 */
public final class ManagerHelper {

    /** Constructor */
    private ManagerHelper() {
        throw new IllegalAccessError("Non-Instantiable");
    }

    /**
     * Placeholder for Feature DTO. Used for internal purposes.
     */
    public static class Feature {
        public String id;
        public long bundleId;
        public String name;
        public String description;
        public boolean isEnabled;
    }

    public static String extractFeatureID(final String id) {
        requireNonNull(id, "Feature ID cannot be null");
        return id.substring(METATYPE_FEATURE_ID_PREFIX.length(), id.length());
    }

    public static FeatureDTO toFeatureDTO(final Feature f) {
        requireNonNull(f, "Feature cannot be null");
        final FeatureDTO feature = new FeatureDTO();
        feature.id = f.id;
        feature.bundleId = f.bundleId;
        feature.name = f.name;
        feature.description = f.description;
        feature.isEnabled = f.isEnabled;
        return feature;
    }

    public static Feature toFeature(final String id, final String name, final String description,
            final String[] defaultValues, final long bundleId) {
        requireNonNull(id, "Feature ID cannot be null");
        final Feature feature = new Feature();
        feature.id = extractFeatureID(id);
        feature.name = name != null ? name : feature.id;
        feature.description = description;
        feature.isEnabled = defaultValues == null ? false : Boolean.valueOf(defaultValues[0]);
        feature.bundleId = bundleId;
        return feature;
    }

    public static List<String> getPIDs(final Bundle bundle, final MetaTypeService metaTypeService) {
        requireNonNull(bundle, "Bundle Instance cannot be null");
        requireNonNull(metaTypeService, "Metatype Service Instance cannot be null");

        final MetaTypeInformation metatypeInfo = metaTypeService.getMetaTypeInformation(bundle);
        return ManagerHelper.asList(metatypeInfo.getPids());
    }

    public static List<AttributeDefinition> getAttributeDefinitions(final Bundle bundle, final String pid,
            final MetaTypeService metaTypeService) {
        requireNonNull(bundle, "Bundle Instance cannot be null");
        requireNonNull(pid, "Configuration PID cannot be null");
        requireNonNull(metaTypeService, "MetaType Service Instance cannot be null");

        final MetaTypeInformation metaTypeInformation = metaTypeService.getMetaTypeInformation(bundle);
        final ObjectClassDefinition ocd = metaTypeInformation.getObjectClassDefinition(pid, null);
        return asList(ocd.getAttributeDefinitions(ALL));
    }

    public static Multimap<String, Feature> getFeaturesFromAttributeDefinitions(final Bundle bundle, final String pid,
            final MetaTypeService metaTypeService) {
        requireNonNull(bundle, "Bundle Instance cannot be null");
        requireNonNull(pid, "Configuration PID cannot be null");
        requireNonNull(metaTypeService, "MetaType Service Instance cannot be null");

        final Multimap<String, Feature> allFeatures = ArrayListMultimap.create();
        for (final AttributeDefinition ad : getAttributeDefinitions(bundle, pid, metaTypeService)) {
            if (ad.getID().startsWith(METATYPE_FEATURE_ID_PREFIX)) {
                //@formatter:off
                allFeatures.put(pid, toFeature(ad.getID(),
                                               ad.getName(),
                                               ad.getDescription(),
                                               ad.getDefaultValue(),
                                               bundle.getBundleId()));
                //@formatter:on
            }
        }
        return allFeatures;
    }

    public static <T> List<T> asList(final T[] elements) {
        return elements == null ? ImmutableList.of() : ImmutableList.copyOf(elements);
    }

}