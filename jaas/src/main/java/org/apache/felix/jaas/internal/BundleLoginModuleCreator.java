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

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.ManifestHeader;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.spi.LoginModule;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * User: chetanm
 * Date: 8/9/12
 * Time: 11:43 AM
 */
@Component
@Service
public class BundleLoginModuleCreator implements LoginModuleCreator, BundleListener {

    private static final String JAAS_MODULE_CLASS = "Jaas-ModuleClass";

    private static Logger log = LoggerFactory.getLogger(BundleLoginModuleCreator.class);

    private final Set<Bundle> loginModuleBundles = new HashSet<Bundle>();
    private Map<String,LoginModuleInfo> loginModuleInfo = Collections.emptyMap();

    private BundleContext bundleContext;


    public LoginModule newInstance(String className) {
        LoginModuleInfo lmInfo = loginModuleInfo.get(className);

        //TODO Rethink about exception handling. Probably introduce custom exception classes
        if(lmInfo == null){
            throw new AssertionError("No bundle exists to create LoginModule from "+className);
        }

        try {
            return lmInfo.newInstance();
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error occurred while creating LoginModule for "+className,e);
        } catch (InstantiationException e) {
            throw new RuntimeException("Error occurred while creating LoginModule for "+className,e);
        }
    }

    public Map<String, LoginModuleInfo> getLoginModuleInfo() {
        return Collections.unmodifiableMap(loginModuleInfo);
    }

    // ---------- BundleListener interface -------------------------------------

    public void bundleChanged(BundleEvent event) {
        Bundle bundle = event.getBundle();
        if (event.getType() == BundleEvent.STARTED
                && providesLoginModule(bundle)) {
            synchronized (this.loginModuleBundles) {
                this.loginModuleBundles.add(event.getBundle());
            }
            this.refreshLoginModuleInfo();
        } else if (event.getType() == BundleEvent.STOPPED) {
            boolean refresh;
            synchronized (this.loginModuleBundles) {
                refresh = this.loginModuleBundles.remove(bundle);
            }
            if (refresh) {
                this.refreshLoginModuleInfo();
            }
        }
    }

    // ---------- SCR integration ----------------------------------------------

    @Activate
    private void activate(BundleContext context){
        this.bundleContext = context;

        this.bundleContext.addBundleListener(this);

        Bundle[] bundles = this.bundleContext.getBundles();
        synchronized (this.loginModuleBundles) {
            for (Bundle bundle : bundles) {
                if (bundle.getState() == Bundle.ACTIVE
                        && providesLoginModule(bundle)) {
                    this.loginModuleBundles.add(bundle);
                }
            }
        }
        
        refreshLoginModuleInfo();
        JaasWebConsolePlugin.setLoginModuleCreator(this);
    }

    private boolean providesLoginModule(Bundle bundle){
        return bundle.getHeaders().get(JAAS_MODULE_CLASS) != null;
    }

    private void refreshLoginModuleInfo() {
        Map<String,LoginModuleInfo> tmpInfo = new HashMap<String, LoginModuleInfo>();
        for(Bundle bundle : loginModuleBundles){
            ManifestHeader mh = ManifestHeader.parse((String) bundle.getHeaders().get(JAAS_MODULE_CLASS));
            for(ManifestHeader.Entry e : mh.getEntries()){
                String className = e.getValue();
                LoginModuleInfo bi = new LoginModuleInfo(className,bundle);
                if(bi.isValid()){

                    //Duplicate registration check
                    if(tmpInfo.containsKey(className)){
                        LoginModuleInfo existingInfo = tmpInfo.get(className);
                        String msg = String.format("LoginModule class %s is already registered with Bundle %s. Entry " +
                                "from bundle %s would be ignored",className,existingInfo.getBundle(),bundle);
                        log.warn(msg);
                        continue;
                    }

                    tmpInfo.put(className,bi);
                    log.info("Registering LoginModule class [{}] from Bundle {}",className,bundle);
                }else{
                    log.warn("Could not load LoginModule class {} from bundle {}",bi.getClassName(),bundle);
                }
            }
        }

        synchronized (this){
            loginModuleInfo = tmpInfo;
        }
    }

    @Deactivate
    private void deactivate(BundleContext context){
        JaasWebConsolePlugin.setLoginModuleCreator(null);
        bundleContext.removeBundleListener(this);

        synchronized (this.loginModuleBundles) {
            loginModuleBundles.clear();
        }
        this.loginModuleInfo.clear();
        this.bundleContext = null;

    }

    static final class LoginModuleInfo {
        private final String className;
        private final Bundle bundle;
        private final Class<LoginModule> clazz;

        public LoginModuleInfo(String className, Bundle bundle) {
            this.className = className;
            this.bundle = bundle;

            Class<LoginModule> clazz = null;
            try {
                clazz = bundle.loadClass(className);
            } catch (ClassNotFoundException e) {
                log.warn("Error loading class ["+className+"] from bundle "+bundle,e);
            }
            this.clazz = clazz;
        }

        public LoginModule newInstance() throws IllegalAccessException, InstantiationException {
            if(clazz == null){
                throw new IllegalStateException("LoginModule class not initialized");
            }
            return clazz.newInstance();
        }

        public boolean isValid(){
            return clazz != null;
        }

        public String getClassName() {
            return className;
        }

        public Bundle getBundle() {
            return bundle;
        }
    }
}
