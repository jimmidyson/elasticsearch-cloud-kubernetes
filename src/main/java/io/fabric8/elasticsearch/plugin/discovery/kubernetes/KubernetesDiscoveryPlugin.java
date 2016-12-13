/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.elasticsearch.plugin.discovery.kubernetes;

import java.io.Closeable;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

import io.fabric8.elasticsearch.cloud.kubernetes.KubernetesAPIService;
import io.fabric8.elasticsearch.cloud.kubernetes.KubernetesAPIServiceImpl;
import io.fabric8.elasticsearch.discovery.kubernetes.KubernetesUnicastHostsProvider;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.zen.UnicastHostsProvider;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.plugins.DiscoveryPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;


public class KubernetesDiscoveryPlugin extends Plugin implements DiscoveryPlugin, Closeable {
  public static final String KUBERNETES = "kubernetes";
  private static Logger logger = Loggers.getLogger(KubernetesDiscoveryPlugin.class);
  private final Settings settings;
  private final SetOnce<KubernetesAPIServiceImpl> kubernetesAPIService = new SetOnce<>();

  static {
    SecurityManager sm = System.getSecurityManager();
    if (sm != null) {
      sm.checkPermission(new SpecialPermission());
    }
  }


  public KubernetesDiscoveryPlugin(Settings settings) {
    this.settings = settings;
    logger.trace("Starting kubernetes discovery plugin...");
  }

  @Override
  public Map<String, Supplier<Discovery>> getDiscoveryTypes(ThreadPool threadPool, TransportService transportService,
                                                            ClusterService clusterService,
                                                            UnicastHostsProvider hostsProvider) {
    if (isDiscoveryAlive(settings, logger)) {
      return Collections.singletonMap(KUBERNETES,
        () -> new ZenDiscovery(settings, threadPool, transportService, clusterService, hostsProvider));
    }
    return null;
  }

  @Override
  public Map<String, Supplier<UnicastHostsProvider>> getZenHostsProviders(TransportService transportService,
                                                                          NetworkService networkService) {
    if (isDiscoveryAlive(settings, logger)) {
      return Collections.singletonMap(KUBERNETES, () -> {
        kubernetesAPIService.set(new KubernetesAPIServiceImpl(settings));
        return new KubernetesUnicastHostsProvider(settings, kubernetesAPIService.get(), transportService, networkService);
      });
    }
    return null;
  }


  @Override
  public List<Setting<?>> getSettings() {
    return Arrays.asList(
      // Register Kubernetes Settings
      KubernetesAPIService.NAME_SPACE_SETTING,
      KubernetesAPIService.SERVICE_NAME_SETTING,
      KubernetesAPIService.REFRESH_SETTING,
      KubernetesAPIService.RETRY_SETTING,
      KubernetesAPIService.MAX_WAIT_SETTING);
  }

  @Override
  public void close() throws IOException {
    IOUtils.close(kubernetesAPIService.get());
  }


  public static boolean isDiscoveryAlive(Settings settings, Logger logger) {
    // User set discovery.type: kubernetes
    if (!KubernetesDiscoveryPlugin.KUBERNETES.equalsIgnoreCase(DiscoveryModule.DISCOVERY_TYPE_SETTING.get(settings))) {
      logger.debug("discovery.type not set to {}", KubernetesDiscoveryPlugin.KUBERNETES);
      return false;
    }

    if (isDefined(settings, KubernetesAPIService.NAME_SPACE_SETTING) &&
      isDefined(settings, KubernetesAPIService.SERVICE_NAME_SETTING)) {
      logger.trace("All required properties for Kubernetes discovery are set!");
      return true;
    } else {
      logger.debug("One or more Kubernetes discovery settings are missing. " +
          "Check elasticsearch.yml file. Should have [{}] and [{}].",
        KubernetesAPIService.NAME_SPACE_SETTING.getKey(),
        KubernetesAPIService.SERVICE_NAME_SETTING.getKey());
      return false;
    }
  }

  private static boolean isDefined(Settings settings, Setting<String> property) throws ElasticsearchException {
    return (property.exists(settings) && Strings.hasText(property.get(settings)));
  }

}
