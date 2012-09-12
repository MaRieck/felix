/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.felix.jaas.internal;

import org.apache.felix.scr.annotations.*;
import org.apache.sling.auth.jaas.LoginModuleFactory;
import org.apache.sling.auth.jaas.ProxyLoginModule;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.ConfigurationSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.*;

/**
 * User: chetanm
 * Date: 8/9/12
 * Time: 12:05 AM
 */
@Component(
        label = "%jaas.spi.name",
        description = "%jaas.spi.description",
        immediate = false,
        metatype = true,
        name = "org.apache.sling.auth.jaas.ConfigurationSpi",
        policy = ConfigurationPolicy.REQUIRE)
@References({
        @Reference(
                name = "LoginModuleFactory",
                referenceInterface = LoginModuleFactory.class,
                cardinality = ReferenceCardinality.MANDATORY_MULTIPLE,
                policy = ReferencePolicy.DYNAMIC)
})
@Service(value = ConfigurationSpi.class)
public class ConfigSpiOsgi extends ConfigurationSpi {
    /**
     * Name of the algorithm to use to fetch JAAS Config
     */
    public static final String JAAS_CONFIG_ALGO_NAME = "JavaLoginConfig";

    private static final AppConfigurationEntry[] EMPTY_ARRAY = new AppConfigurationEntry[0];
    private final Set<ServiceReference> unhandledProviders = new HashSet<ServiceReference>();
    private final Map<ServiceReference, LoginModuleProvider> providerMap = new HashMap<ServiceReference, LoginModuleProvider>();

    private BundleContext context;
    private Map<String,List<AppConfigurationHolder>> configs = Collections.emptyMap();

    private Logger log = LoggerFactory.getLogger(getClass());

    @Property
    private static final String JAAS_DEFAULT_REALM_NAME = "jaas.defaultRealmName";

    private String defaultRealmName;


    private static final String DEFAULT_CONFIG_PROVIDER_NAME = "JavaLoginConfigOsgi";
    @Property(value = DEFAULT_CONFIG_PROVIDER_NAME)
    private static final String JAAS_CONFIG_PROVIDER_NAME = "jaas.configProviderName";

    private String jaasConfigProviderName;

    @Override
    protected AppConfigurationEntry[] engineGetAppConfigurationEntry(String name) {
        List<AppConfigurationHolder> configHolders = configs.get(name);
        if(configHolders == null){
            log.warn("No JAAS module configured for realm {}",name);
            return EMPTY_ARRAY;
        }

        AppConfigurationEntry[] entries = new AppConfigurationEntry[configHolders.size()];
        for(int i = 0; i < configHolders.size(); i++){
            entries[i] = configHolders.get(i).getEntry();
        }
        return entries;
    }

    private void recreateConfigs(){
        Map<String,List<AppConfigurationHolder>> realmToConfigMap = new HashMap<String,List<AppConfigurationHolder>>();
        for(LoginModuleProvider lmfExt : providerMap.values()){
            String realmName = lmfExt.realmName();
            if(realmName == null){
                realmName = defaultRealmName;
            }

            List<AppConfigurationHolder> configList = realmToConfigMap.get(realmName);
            if(configList == null){
                configList = new ArrayList<AppConfigurationHolder>();
                realmToConfigMap.put(realmName,configList);
            }

            configList.add(new AppConfigurationHolder(lmfExt));
        }

        for(List<AppConfigurationHolder> configHolders : realmToConfigMap.values()){
            Collections.sort(configHolders);
        }

        this.configs = realmToConfigMap;
    }

    public Map<String,List<AppConfigurationHolder>> getAllConfiguration(){
        return Collections.unmodifiableMap(configs);
    }

    // ---------- SCR integration ----------------------------------------------

    @Activate
    private void activate(BundleContext context, Map config) {
        this.defaultRealmName = PropertiesUtil.toString(config.get(JAAS_DEFAULT_REALM_NAME),null);
        if(defaultRealmName == null){
            throw new IllegalArgumentException("Default JAAS realm name must be specified");
        }

        this.jaasConfigProviderName =  PropertiesUtil.toString(config.get(JAAS_CONFIG_PROVIDER_NAME), DEFAULT_CONFIG_PROVIDER_NAME);

        this.context = context;
        synchronized (unhandledProviders) {
            for (ServiceReference ref : unhandledProviders) {
                LoginModuleFactory pvd = (LoginModuleFactory) context.getService(ref);
                registerFactory(ref, pvd);
            }
            unhandledProviders.clear();
            recreateConfigs();
        }

        registerProvider();
        JaasWebConsolePlugin.setConfigSpi(this);
    }

    @Deactivate
    private void deactivate() {
        this.context = null;
        JaasWebConsolePlugin.setConfigSpi(null);
        deregisterProvider();

        synchronized (unhandledProviders) {
            unhandledProviders.clear();
            configs.clear();
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    private void bindLoginModuleFactory(ServiceReference ref) {
        synchronized (unhandledProviders) {
            if (context == null) {
                unhandledProviders.add(ref);
            } else {
                LoginModuleFactory lmf = (LoginModuleFactory) context.getService(ref);
                registerFactory(ref, lmf);
                recreateConfigs();
            }
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    private void unbindLoginModuleFactory(ServiceReference ref) {
        synchronized (unhandledProviders) {
            if (context == null) {
                unhandledProviders.remove(ref);
            } else {
                deregisterFactory(ref);
                recreateConfigs();
            }
        }
    }

    // ---------- JAAS/JCA/Security ----------------------------------------------

    private void deregisterFactory(ServiceReference ref) {
        LoginModuleProvider lmp = providerMap.remove(ref);
        if(lmp != null){
            log.info("Deregistering LoginModuleFactory {}",lmp);
        }
    }

    private void registerFactory(ServiceReference ref, LoginModuleFactory lmf) {
        LoginModuleProvider lmfExt = null;
        if(lmf instanceof LoginModuleProvider){
            lmfExt = (LoginModuleProvider) lmf;
        }else{
            lmfExt = new OsgiLoginModuleProvider(ref,lmf);
        }
        log.info("Registering LoginModuleFactory {}",lmf);
        providerMap.put(ref, lmfExt);
    }

    private void registerProvider(){
        Security.addProvider(new OSGiProvider());
        log.info("Registered provider {} for managing JAAS config with type {}",jaasConfigProviderName,JAAS_CONFIG_ALGO_NAME);
    }

    private void deregisterProvider(){
        Security.removeProvider(jaasConfigProviderName);
        log.info("Removed provider {} type {} from Security providers list", jaasConfigProviderName,JAAS_CONFIG_ALGO_NAME);
    }

    private class OSGiProvider extends Provider {
        public static final String TYPE_CONFIGURATION = "Configuration";

        protected OSGiProvider() {
            super(jaasConfigProviderName, 1.0, "OSGi based provider for Jaas configuration");
        }

        @Override
        public synchronized Service getService(String type, String algorithm) {
            if(TYPE_CONFIGURATION.equals(type)
                    && JAAS_CONFIG_ALGO_NAME.equals(algorithm)){
                return new ConfigurationService(this);
            }
            return super.getService(type, algorithm);
        }
    }

    private class ConfigurationService extends Provider.Service  {

        public ConfigurationService(Provider provider) {
            super(provider,
                    OSGiProvider.TYPE_CONFIGURATION,  //the type of this service
                    JAAS_CONFIG_ALGO_NAME,            //the algorithm name
                    ConfigSpiOsgi.class.getName(),    //the name of the class implementing this service
                    Collections.<String>emptyList(),  //List of aliases or null if algorithm has no aliases
                    Collections.<String,String>emptyMap()); //Map of attributes or null if this implementation
        }

        @Override
        public Object newInstance(Object constructorParameter) throws NoSuchAlgorithmException {
            //constructorParameter is the one which is passed as Configuration.Parameters params
            //for now we do not make use of that
            return ConfigSpiOsgi.this;
        }
    }
    static final class AppConfigurationHolder implements Comparable<AppConfigurationHolder> {
        private static final String LOGIN_MODULE_CLASS = ProxyLoginModule.class.getName();
        private final LoginModuleProvider provider;
        private final int ranking;
        private final AppConfigurationEntry entry;

        public AppConfigurationHolder(LoginModuleProvider provider) {
            this.provider = provider;
            this.ranking = provider.ranking();

            Map<String,Object> options = new HashMap<String,Object>(provider.options());
            options.put(ProxyLoginModule.PROP_LOGIN_MODULE_FACTORY, provider);
            this.entry = new AppConfigurationEntry(LOGIN_MODULE_CLASS,
                    provider.getControlFlag(), Collections.unmodifiableMap(options));
        }

        public int compareTo(AppConfigurationHolder that) {
            if (this.ranking == that.ranking) {
                return 0;
            }
            return this.ranking > that.ranking ? -1 : 1;
        }

        public AppConfigurationEntry getEntry() {
            return entry;
        }

        public LoginModuleProvider getProvider() {
            return provider;
        }
    }
}
