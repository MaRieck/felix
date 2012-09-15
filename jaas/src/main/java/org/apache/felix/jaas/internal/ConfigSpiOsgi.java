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

import org.apache.felix.jaas.LoginModuleFactory;
import org.apache.felix.jaas.boot.ProxyLoginModule;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Property;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.ConfigurationSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@Component(
        label = "%jaas.spi.name",
        description = "%jaas.spi.description",
        metatype = true,
        ds=false,
        name = "org.apache.felix.jaas.ConfigurationSpi",
        policy = ConfigurationPolicy.REQUIRE)
public class ConfigSpiOsgi extends ConfigurationSpi implements ManagedService, ServiceTrackerCustomizer {
    /**
     * Name of the algorithm to use to fetch JAAS Config
     */
    public static final String JAAS_CONFIG_ALGO_NAME = "JavaLoginConfig";

    public static final String SERVICE_PID = "org.apache.felix.jaas.ConfigurationSpi";

    private Map<String,Realm> configs = Collections.emptyMap();

    private final Logger log;

    @Property
    private static final String JAAS_DEFAULT_REALM_NAME = "jaas.defaultRealmName";
    private String defaultRealmName;

    private static final String DEFAULT_CONFIG_PROVIDER_NAME = "FelixJaasProvider";
    @Property(value = DEFAULT_CONFIG_PROVIDER_NAME)
    private static final String JAAS_CONFIG_PROVIDER_NAME = "jaas.configProviderName";

    private final Map<ServiceReference,LoginModuleProvider> providerMap =
            new ConcurrentHashMap<ServiceReference, LoginModuleProvider>();

    private String jaasConfigProviderName;

    private final Object lock = new Object();

    private final BundleContext context;

    private final ServiceTracker tracker;

    private ServiceRegistration spiReg;

    public ConfigSpiOsgi(BundleContext context,Logger log) {
        this.context = context;
        this.log = log;
        this.tracker = new ServiceTracker(context,LoginModuleFactory.class.getName(),this);

        Properties props = new Properties();
        props.setProperty(Constants.SERVICE_VENDOR, "Apache Software Foundation");
        props.setProperty(Constants.SERVICE_PID, SERVICE_PID);

        this.context.registerService(ManagedService.class.getName(),this,props);
    }

    @Override
    protected AppConfigurationEntry[] engineGetAppConfigurationEntry(String name) {
        Realm realm = configs.get(name);
        if(realm == null){
            log.log(LogService.LOG_WARNING,"No JAAS module configured for realm "+name);
            return null;
        }

        return realm.engineGetAppConfigurationEntry();
    }

    Map<String,Realm> getAllConfiguration(){
        return configs;
    }

    private void recreateConfigs(){
        Map<String,Realm> realmToConfigMap = new HashMap<String,Realm>();
        for(LoginModuleProvider lmp : providerMap.values()){
            String realmName = lmp.realmName();
            if(realmName == null){
                realmName = defaultRealmName;
            }

            Realm realm = realmToConfigMap.get(realmName);
            if(realm == null){
                realm = new Realm(realmName);
                realmToConfigMap.put(realmName,realm);
            }

            realm.add(new AppConfigurationHolder(lmp));
        }

        for(Realm realm : realmToConfigMap.values()){
            realm.afterPropertiesSet();
        }

        //We also register the Spi with OSGI SR if any configuration is available
        //This would allow any client component to determine when it should start
        //and use the config
        if(!realmToConfigMap.isEmpty() && spiReg == null){
            Properties props = new Properties();
            props.setProperty("providerName", "felix");

            synchronized (lock){
                spiReg = context.registerService(ConfigurationSpi.class.getName(), this, props);
            }
        }

        synchronized (lock){
            this.configs = Collections.unmodifiableMap(realmToConfigMap);
        }
    }


    //--------------LifeCycle methods -------------------------------------

    void open(){
        this.tracker.open();
    }

    void close() {
        this.tracker.close();
        deregisterProvider();

        synchronized (lock) {
            providerMap.clear();
            configs.clear();
        }
    }

    // --------------Config handling ----------------------------------------

    @Override
    public void updated(Dictionary properties) throws ConfigurationException {
        String newDefaultRealmName = Util.toString(properties.get(JAAS_DEFAULT_REALM_NAME),null);
        if(newDefaultRealmName == null){
            throw new IllegalArgumentException("Default JAAS realm name must be specified");
        }

        if(!newDefaultRealmName.equals(defaultRealmName)){
            defaultRealmName = newDefaultRealmName;
            recreateConfigs();
        }

        this.jaasConfigProviderName =  Util.toString(properties.get(JAAS_CONFIG_PROVIDER_NAME),
                DEFAULT_CONFIG_PROVIDER_NAME);

        deregisterProvider();
        registerProvider();
    }


    // --------------JAAS/JCA/Security ----------------------------------------

    private void registerProvider(){
        Security.addProvider(new OSGiProvider());
        log.log(LogService.LOG_INFO, "Registered provider " + jaasConfigProviderName
                + " for managing JAAS config with type " + JAAS_CONFIG_ALGO_NAME);
    }

    private void deregisterProvider(){
        Security.removeProvider(jaasConfigProviderName);
        log.log(LogService.LOG_INFO, "Removed provider " + jaasConfigProviderName + " type "
                + JAAS_CONFIG_ALGO_NAME + " from Security providers list");
    }

    // ---------- ServiceTracker ----------------------------------------------

    @Override
    public Object addingService(ServiceReference reference) {
        LoginModuleFactory lmf = (LoginModuleFactory) context.getService(reference);
        registerFactory(reference,lmf);
        return lmf;
    }

    @Override
    public void modifiedService(ServiceReference reference, Object service) {
        recreateConfigs();
    }

    @Override
    public void removedService(ServiceReference reference, Object service) {
        deregisterFactory(reference);
        recreateConfigs();
        context.ungetService(reference);
    }

    private void deregisterFactory(ServiceReference ref) {
        LoginModuleProvider lmp = providerMap.remove(ref);
        if(lmp != null){
            log.log(LogService.LOG_INFO, "Deregistering LoginModuleFactory " + lmp);
        }
    }

    private void registerFactory(ServiceReference ref, LoginModuleFactory lmf) {
        LoginModuleProvider lmfExt;
        if(lmf instanceof LoginModuleProvider){
            lmfExt = (LoginModuleProvider) lmf;
        }else{
            lmfExt = new OsgiLoginModuleProvider(ref,lmf);
        }
        log.log(LogService.LOG_INFO, "Registering LoginModuleFactory " + lmf);
        providerMap.put(ref, lmfExt);
    }

    private class OSGiProvider extends Provider {
        public static final String TYPE_CONFIGURATION = "Configuration";

        OSGiProvider() {
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

    static final class Realm {
        private final String realmName;
        private AppConfigurationEntry[] configArray;
        private List<AppConfigurationHolder> configs = new ArrayList<AppConfigurationHolder>();

        Realm(String realmName) {
            this.realmName = realmName;
        }

        public void add(AppConfigurationHolder config){
            configs.add(config);
        }

        public void afterPropertiesSet(){
            Collections.sort(configs);
            configArray = new AppConfigurationEntry[configs.size()];
            for(int i = 0; i < configs.size(); i++){
                configArray[i] = configs.get(i).getEntry();
            }
            configs = Collections.unmodifiableList(configs);
        }

        public String getRealmName() {
            return realmName;
        }

        public List<AppConfigurationHolder> getConfigs() {
            return configs;
        }

        public AppConfigurationEntry[] engineGetAppConfigurationEntry(){
            return Arrays.copyOf(configArray,configArray.length);
        }

        @Override
        public String toString() {
            return "Realm{" +
                    "realmName='" + realmName + '\'' +
                    '}';
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
