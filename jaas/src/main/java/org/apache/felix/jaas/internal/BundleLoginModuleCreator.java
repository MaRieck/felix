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

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.util.tracker.BundleTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.spi.LoginModule;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


class BundleLoginModuleCreator extends BundleTracker implements LoginModuleCreator {

    private static final String JAAS_MODULE_CLASS = "Jaas-ModuleClass";

    private static Logger log = LoggerFactory.getLogger(BundleLoginModuleCreator.class);

    private Map<String,LoginModuleInfo> loginModuleInfo = new ConcurrentHashMap<String, LoginModuleInfo>();


    public BundleLoginModuleCreator(BundleContext context) {
        super(context,Bundle.ACTIVE,null /* customizer */);
    }


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

    public Map<Bundle, Set<String>> getBundleToLoginModuleMapping() {
        //With R5 we could have used Map<Bundle, T> BundleTracker.getTracked()
        //Determine the bundle -> login module classes map
        Map<Bundle,Set<String>> bundleMap = new HashMap<Bundle, Set<String>>();
        for(LoginModuleInfo e : loginModuleInfo.values()){
            Bundle b = e.getBundle();
            @SuppressWarnings("unchecked")
            Set<String> classNames = (Set<String>) getObject(b);

            if(classNames == null){
                continue;
            }
            bundleMap.put(b,classNames);
        }
        return bundleMap;
    }


    // ---------- BundleTracker integration ----------------------------------------------

    @Override
    public Object addingBundle(Bundle bundle, BundleEvent event) {
        if (providesLoginModule(bundle)) {
            registerBundle(bundle);
        }
        return null;
    }

    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, Object object) {
        @SuppressWarnings("unchecked")
        Set<String> classNames = (Set<String>) object;

        for(String className : classNames){
            loginModuleInfo.remove(className);
        }
    }

    private boolean providesLoginModule(Bundle bundle){
        return bundle.getHeaders().get(JAAS_MODULE_CLASS) != null;
    }

    private Set<String> registerBundle(Bundle bundle){
        Set<String> classNames = Util.parseHeader((String) bundle.getHeaders().get(JAAS_MODULE_CLASS));
        for(String className : classNames){
            LoginModuleInfo bi = new LoginModuleInfo(className,bundle);
            if(bi.isValid()){

                //Duplicate registration check
                if(loginModuleInfo.containsKey(className)){
                    LoginModuleInfo existingInfo = loginModuleInfo.get(className);
                    String msg = String.format("LoginModule class %s is already registered with Bundle %s. Entry " +
                            "from bundle %s would be ignored",className,existingInfo.getBundle(),bundle);
                    log.warn(msg);
                    continue;
                }

                loginModuleInfo.put(className,bi);
                log.info("Registering LoginModule class [{}] from Bundle {}",className,bundle);
            }else{
                log.warn("Could not load LoginModule class {} from bundle {}",bi.getClassName(),bundle);
            }
        }
        return classNames;
    }

    static final class LoginModuleInfo {
        private final String className;
        private final Bundle bundle;
        private final Class<LoginModule> clazz;

        @SuppressWarnings("unchecked")
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
