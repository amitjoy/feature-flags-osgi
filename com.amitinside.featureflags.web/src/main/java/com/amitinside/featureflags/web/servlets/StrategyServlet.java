/*******************************************************************************
 * Copyright (c) 2017 Amit Kumar Mondal
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package com.amitinside.featureflags.web.servlets;

import static com.amitinside.featureflags.StrategyFactory.StrategyType.SERVICE_PROPERTY;
import static javax.servlet.http.HttpServletResponse.*;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amitinside.featureflags.FeatureService;
import com.amitinside.featureflags.StrategyFactory;
import com.amitinside.featureflags.strategy.ActivationStrategy;
import com.amitinside.featureflags.web.FeatureFlagsServlet;
import com.amitinside.featureflags.web.util.HttpServletRequestHelper;
import com.google.common.collect.Maps;
import com.google.gson.Gson;

@Component(name = "StrategyServlet", immediate = true)
public final class StrategyServlet extends HttpServlet implements FeatureFlagsServlet {

    private static final String ALIAS = "strategies";
    private static final long serialVersionUID = 7683703693369965631L;

    /** Logger Instance */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private FeatureService featureService;
    private final Gson gson = new Gson();
    private final Map<ActivationStrategy, Map<String, Object>> strategyProperties = Maps.newHashMap();

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) {
        final List<String> uris = HttpServletRequestHelper.parseFullUrl(req);
        if (uris.size() == 1 && uris.get(0).equalsIgnoreCase(ALIAS)) {
            final List<StrategyData> data = featureService.getStrategies().map(this::mapToStrategyData)
                    .collect(Collectors.toList());
            final String json = gson.toJson(new DataHolder(data));
            resp.setStatus(SC_OK);
            try (final PrintWriter writer = resp.getWriter()) {
                writer.write(json);
            } catch (final IOException e) {
                logger.error("{}", e.getMessage(), e);
                resp.setStatus(SC_INTERNAL_SERVER_ERROR);
                return;
            }
        }
        if (uris.size() == 2 && uris.get(0).equalsIgnoreCase(ALIAS)) {
            final StrategyData data = featureService.getStrategy(uris.get(1)).map(this::mapToStrategyData).orElse(null);

            final String json = gson.toJson(data);
            resp.setStatus(SC_OK);
            try (PrintWriter writer = resp.getWriter()) {
                writer.write(json);
            } catch (final IOException e) {
                logger.error("{}", e.getMessage(), e);
                resp.setStatus(SC_INTERNAL_SERVER_ERROR);
                return;
            }
        }
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) {
        final List<String> uris = HttpServletRequestHelper.parseFullUrl(req);
        if (uris.size() == 1 && uris.get(0).equalsIgnoreCase(ALIAS)) {
            String jsonData = null;
            try (final BufferedReader reader = req.getReader()) {
                jsonData = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            } catch (final IOException e) {
                logger.error("{}", e.getMessage(), e);
                resp.setStatus(SC_INTERNAL_SERVER_ERROR);
                return;
            }
            final StrategyData json = gson.fromJson(jsonData, StrategyData.class);
            //@formatter:off
            final StrategyFactory factory = StrategyFactory.make(json.getName(), SERVICE_PROPERTY,
                                      c -> c.withDescription(json.getDescription())
                                            .withKey(json.getKey())
                                            .withValue(json.getValue())
                                            .build());
            //@formatter:on
            final Optional<String> pid = featureService.createPropertyBasedStrategy(factory);
            if (pid.isPresent()) {
                resp.setStatus(SC_OK);
                try (PrintWriter writer = resp.getWriter()) {
                    writer.write(pid.get());
                } catch (final IOException e) {
                    logger.error("{}", e.getMessage(), e);
                    resp.setStatus(SC_INTERNAL_SERVER_ERROR);
                    return;
                }
            } else {
                resp.setStatus(SC_INTERNAL_SERVER_ERROR);
            }
        }

    }

    @Override
    protected void doDelete(final HttpServletRequest req, final HttpServletResponse resp) {
        final List<String> uris = HttpServletRequestHelper.parseFullUrl(req);
        if (uris.size() == 2 && uris.get(0).equalsIgnoreCase("features")) {
            final String name = uris.get(1);
            featureService.removePropertyBasedStrategy(name);
            resp.setStatus(SC_OK);
        }
    }

    /**
     * {@link FeatureService} service binding callback
     */
    @Reference
    protected void setFeatureService(final FeatureService featureService) {
        this.featureService = featureService;
    }

    /**
     * {@link FeatureService} service unbinding callback
     */
    protected void unsetFeatureService(final FeatureService featureService) {
        this.featureService = null;
    }

    /**
     * {@link ActivationStrategy} service binding callback
     */
    @Reference(cardinality = MULTIPLE, policy = DYNAMIC)
    protected void bindStrategy(final ActivationStrategy strategy, final Map<String, Object> props) {
        strategyProperties.put(strategy, props);
    }

    /**
     * {@link ActivationStrategy} service unbinding callback
     */
    protected void unbindStrategy(final ActivationStrategy strategy, final Map<String, Object> props) {
        strategyProperties.remove(strategy);
    }

    private static final class DataHolder {
        private final List<StrategyData> strategies;

        public DataHolder(final List<StrategyData> strategies) {
            this.strategies = strategies;
        }

        @SuppressWarnings("unused")
        public List<StrategyData> getFeatures() {
            return strategies;
        }
    }

    /**
     * Internal class used to represent Strategy JSON data
     */
    private static final class StrategyData {
        private final String name;
        private final String description;
        private final String key;
        private final String value;

        public StrategyData(final String name, final String description, final String key, final String value) {
            this.name = name;
            this.description = description;
            this.key = key;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

    }

    private StrategyData mapToStrategyData(final ActivationStrategy strategy) {
        final String name = strategy.getName();
        final String description = strategy.getDescription().orElse(null);
        final Map<String, Object> props = strategyProperties.get(strategy);
        return new StrategyData(name, description, (String) props.get("property_key"),
                (String) props.get("property_value"));
    }

    @Override
    public String getAlias() {
        return "/" + ALIAS;
    }
}
