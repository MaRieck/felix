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
package org.apache.felix.dm.annotation.plugin.bnd;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.felix.dm.annotation.api.AdapterService;
import org.apache.felix.dm.annotation.api.AspectService;
import org.apache.felix.dm.annotation.api.BundleAdapterService;
import org.apache.felix.dm.annotation.api.BundleDependency;
import org.apache.felix.dm.annotation.api.Component;
import org.apache.felix.dm.annotation.api.Composition;
import org.apache.felix.dm.annotation.api.ConfigurationDependency;
import org.apache.felix.dm.annotation.api.Destroy;
import org.apache.felix.dm.annotation.api.FactoryConfigurationAdapterService;
import org.apache.felix.dm.annotation.api.Init;
import org.apache.felix.dm.annotation.api.Inject;
import org.apache.felix.dm.annotation.api.LifecycleController;
import org.apache.felix.dm.annotation.api.ResourceAdapterService;
import org.apache.felix.dm.annotation.api.ResourceDependency;
import org.apache.felix.dm.annotation.api.ServiceDependency;
import org.apache.felix.dm.annotation.api.Start;
import org.apache.felix.dm.annotation.api.Registered;
import org.apache.felix.dm.annotation.api.Stop;
import org.apache.felix.dm.annotation.api.Unregistered;
import org.osgi.framework.Bundle;

import aQute.lib.osgi.Annotation;
import aQute.lib.osgi.ClassDataCollector;
import aQute.lib.osgi.Verifier;

/**
 * This is the scanner which does all the annotation parsing on a given class.
 * To start the parsing, just invoke the parseClassFileWithCollector and finish methods.
 * Once parsed, the corresponding component descriptors can be built using the "writeTo" method.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class AnnotationCollector extends ClassDataCollector
{
    private final static String A_INIT = "L" + Init.class.getName().replace('.', '/') + ";";
    private final static String A_START = "L" + Start.class.getName().replace('.', '/') + ";";
    private final static String A_STOP = "L" + Stop.class.getName().replace('.', '/') + ";";
    private final static String A_DESTROY = "L" + Destroy.class.getName().replace('.', '/') + ";";
    private final static String A_COMPOSITION = "L" + Composition.class.getName().replace('.', '/') + ";";
    private final static String A_LIFCLE_CTRL = "L" + LifecycleController.class.getName().replace('.', '/')+ ";";

    private final static String A_COMPONENT = "L" + Component.class.getName().replace('.', '/') + ";";
    private final static String A_SERVICE_DEP = "L" + ServiceDependency.class.getName().replace('.', '/') + ";";
    private final static String A_CONFIGURATION_DEPENDENCY = "L" + ConfigurationDependency.class.getName().replace('.', '/') + ";";
    private final static String A_BUNDLE_DEPENDENCY = "L" + BundleDependency.class.getName().replace('.', '/') + ";";
    private final static String A_RESOURCE_DEPENDENCY = "L" + ResourceDependency.class.getName().replace('.', '/') + ";";
    private final static String A_ASPECT_SERVICE = "L"+ AspectService.class.getName().replace('.', '/') + ";";
    private final static String A_ADAPTER_SERVICE = "L" + AdapterService.class.getName().replace('.', '/') + ";";
    private final static String A_BUNDLE_ADAPTER_SERVICE = "L" + BundleAdapterService.class.getName().replace('.', '/') + ";";
    private final static String A_RESOURCE_ADAPTER_SERVICE = "L" + ResourceAdapterService.class.getName().replace('.', '/') + ";";
    private final static String A_FACTORYCONFIG_ADAPTER_SERVICE = "L" + FactoryConfigurationAdapterService.class.getName().replace('.', '/') + ";";
    private final static String A_INJECT = "L" + Inject.class.getName().replace('.', '/') + ";";
    private final static String A_REGISTERED = "L" + Registered.class.getName().replace('.', '/') + ";";
    private final static String A_UNREGISTERED = "L" + Unregistered.class.getName().replace('.', '/') + ";";

    private Logger m_logger;
    private String m_className;
    private String[] m_interfaces;
    private boolean m_isField;
    private String m_field;
    private String m_method;
    private String m_descriptor;
    private Set<String> m_methods = new HashSet<String>();
    private Set<String> m_dependencyNames = new HashSet<String>();
    private List<EntryWriter> m_writers = new ArrayList<EntryWriter>(); // Last elem is either Service or AspectService
    private MetaType m_metaType;
    private String m_startMethod;
    private String m_stopMethod;
    private String m_initMethod;
    private String m_destroyMethod;
    private String m_compositionMethod;
    private String m_starter;
    private String m_stopper;
    private Set<String> m_importService = new HashSet<String>();
    private Set<String> m_exportService = new HashSet<String>();
    private String m_bundleContextField;
    private String m_dependencyManagerField;
    private String m_componentField;
    private String m_registeredMethod;
    private String m_unregisteredMethod;

    /**
     * This class represents a DependencyManager component descriptor entry.
     * (Service, a ServiceDependency ... see EntryType enum).
     */

    /**
     * Makes a new Collector for parsing a given class.
     * @param reporter the object used to report logs.
     */
    public AnnotationCollector(Logger reporter, MetaType metaType)
    {
        m_logger = reporter;
        m_metaType = metaType;
    }

    /**
     * Parses the name of the class.
     * @param access the class access
     * @param name the class name (package are "/" separated).
     */
    @Override
    public void classBegin(int access, String name)
    {
        m_className = name.replace('/', '.');
        m_logger.debug("class name: %s", m_className);
    }

    /**
     * Parses the implemented interfaces ("/" separated).
     */
    @Override
    public void implementsInterfaces(String[] interfaces)
    {
        m_interfaces = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++)
        {
            m_interfaces[i] = interfaces[i].replace('/', '.');
        }
        m_logger.debug("implements: %s", Arrays.toString(m_interfaces));
    }

    /**
     * Parses a method. Always invoked BEFORE eventual method annotation.
     */
    @Override
    public void method(int access, String name, String descriptor)
    {
        m_logger.debug("Parsed method %s, descriptor=%s", name, descriptor);
        m_isField = false;
        m_method = name;
        m_descriptor = descriptor;
        m_methods.add(name + descriptor);
    }

    /**
     * Parses a field. Always invoked BEFORE eventual field annotation
     */
    @Override
    public void field(int access, String name, String descriptor)
    {
        m_logger.debug("Parsed field %s, descriptor=%s", name, descriptor);
        m_isField = true;
        m_field = name;
        m_descriptor = descriptor;
    }

    /** 
     * An annotation has been parsed. Always invoked AFTER the "method"/"field"/"classBegin" callbacks. 
     */
    @Override
    public void annotation(Annotation annotation)
    {
        m_logger.debug("Parsed annotation: %s", annotation);

        if (annotation.getName().equals(A_COMPONENT))
        {
            parseComponentAnnotation(annotation);
        }
        else if (annotation.getName().equals(A_ASPECT_SERVICE))
        {
            parseAspectService(annotation);
        }
        else if (annotation.getName().equals(A_ADAPTER_SERVICE))
        {
            parseAdapterService(annotation);
        }
        else if (annotation.getName().equals(A_BUNDLE_ADAPTER_SERVICE))
        {
            parseBundleAdapterService(annotation);
        }
        else if (annotation.getName().equals(A_RESOURCE_ADAPTER_SERVICE))
        {
            parseResourceAdapterService(annotation);
        }
        else if (annotation.getName().equals(A_FACTORYCONFIG_ADAPTER_SERVICE))
        {
            parseFactoryConfigurationAdapterService(annotation);
        }
        else if (annotation.getName().equals(A_INIT))
        {
            m_initMethod = m_method;
        } 
        else if (annotation.getName().equals(A_START))
        {
            m_startMethod = m_method;
        } 
        else if (annotation.getName().equals(A_REGISTERED))
        {
            m_registeredMethod = m_method;
        }
        else if (annotation.getName().equals(A_STOP))
        {
            m_stopMethod = m_method;
        }
        else if (annotation.getName().equals(A_UNREGISTERED))
        {
            m_unregisteredMethod = m_method;
        }
        else if (annotation.getName().equals(A_DESTROY))
        {
            m_destroyMethod = m_method;
        }
        else if (annotation.getName().equals(A_COMPOSITION))
        {
            Patterns.parseMethod(m_method, m_descriptor, Patterns.COMPOSITION);
            m_compositionMethod = m_method;
        } else if (annotation.getName().equals(A_LIFCLE_CTRL)) 
        {
            parseLifecycleAnnotation(annotation);
        }
        else if (annotation.getName().equals(A_SERVICE_DEP))
        {
            parseServiceDependencyAnnotation(annotation);
        }
        else if (annotation.getName().equals(A_CONFIGURATION_DEPENDENCY))
        {
            parseConfigurationDependencyAnnotation(annotation);
        }
        else if (annotation.getName().equals(A_BUNDLE_DEPENDENCY))
        {
            parseBundleDependencyAnnotation(annotation);
        }
        else if (annotation.getName().equals(A_RESOURCE_DEPENDENCY))
        {
            parseRersourceDependencyAnnotation(annotation);
        } 
        else if (annotation.getName().equals(A_INJECT))
        {
            parseInject(annotation);
        }
    }

    /**
     * Finishes up the class parsing. This method must be called once the parseClassFileWithCollector method has returned.
     * @return true if some annotations have been parsed, false if not.
     */
    public boolean finish()
    {
        if (m_writers.size() == 0)
        {
            return false;
        }

        // We must have at least a Service annotation.
        checkServiceDeclared(EntryType.Component, EntryType.AspectService, EntryType.AdapterService,
            EntryType.BundleAdapterService,
            EntryType.ResourceAdapterService, EntryType.FactoryConfigurationAdapterService);
        
        StringBuilder sb = new StringBuilder();
        sb.append("Parsed annotation for class ");
        sb.append(m_className);
        for (int i = m_writers.size() - 1; i >= 0; i--)
        {
            sb.append("\n\t").append(m_writers.get(i).toString());
        }
        m_logger.info(sb.toString());
        return true;
    }

    /**
     * Writes the generated component descriptor in the given print writer.
     * The first line must be the service (@Service or AspectService).
     * @param pw the writer where the component descriptor will be written.
     */
    public void writeTo(PrintWriter pw)
    {
        // The last element our our m_writers list contains either the Service, or the AspectService descriptor.
        for (int i = m_writers.size() - 1; i >= 0; i--)
        {
            pw.println(m_writers.get(i).toString());
        }
    }
        
    /**
     * Returns list of all imported services. Imported services are deduced from every
     * @ServiceDependency annotations.
     * @return the list of imported services, or null
     */
    public Set<String> getImportService()
    {
        return m_importService;
    }

    /**
     * Returns list of all exported services. Imported services are deduced from every
     * annotations which provides a service (@Component, etc ...)
     * @return the list of exported services, or null
     */
    public Set<String> getExportService()
    {
        return m_exportService;
    }

    private void parseComponentAnnotation(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.Component);
        m_writers.add(writer);

        // Register previously parsed annotations common to services (Init/Start/...)
        addCommonServiceParams(writer);

        // impl attribute
        writer.put(EntryParam.impl, m_className);

        // properties attribute
        parseProperties(annotation, EntryParam.properties, writer);

        // provides attribute.
        if (writer.putClassArray(annotation, EntryParam.provides, m_interfaces, m_exportService) == 0)
        {
            // no service provided: check if @Registered/@Unregistered annotation are used
            // and raise an error if true.
            checkRegisteredUnregisteredNotPresent();
        }

        // factorySet attribute
        String factorySetName = writer.putString(annotation, EntryParam.factorySet, null);
        if (factorySetName != null)
        {
            // When a component defines a factorySet, it means that a java.util.Set will 
            // be provided into the OSGi registry, in order to let anoter component add
            // some component instance configurations into it.
            // So, we have to indicate that the Set is provided as a service, in the Export-Serviec
            // header.
            m_exportService.add("java.util.Set");
        }

        // factoryConfigure attribute
        writer.putString(annotation, EntryParam.factoryConfigure, null);
        
        // factoryMethod attribute
        writer.putString(annotation, EntryParam.factoryMethod, null);
    }

    private void addCommonServiceParams(EntryWriter writer)
    {
        if (m_initMethod != null)
        {
            writer.put(EntryParam.init, m_initMethod);
        }

        if (m_startMethod != null)
        {
            writer.put(EntryParam.start, m_startMethod);
        }
        
        if (m_registeredMethod != null)
        {
            writer.put(EntryParam.registered, m_registeredMethod);
        }

        if (m_stopMethod != null)
        {
            writer.put(EntryParam.stop, m_stopMethod);
        }
        
        if (m_unregisteredMethod != null)
        {
            writer.put(EntryParam.unregistered, m_unregisteredMethod);
        }

        if (m_destroyMethod != null)
        {
            writer.put(EntryParam.destroy, m_destroyMethod);
        }

        if (m_compositionMethod != null)
        {
            writer.put(EntryParam.composition, m_compositionMethod);
        }       
        
        if (m_starter != null) 
        {
            writer.put(EntryParam.starter, m_starter);
        }
        
        if (m_stopper != null)
        {
            writer.put(EntryParam.stopper, m_stopper);
            if (m_starter == null)
            {
                throw new IllegalArgumentException("Can't use a @LifecycleController annotation for stopping a service without declaring a " +
                                                   "@LifecycleController that starts the component in class " + m_className);
            }
        }   

        if (m_bundleContextField != null)
        {
            writer.put(EntryParam.bundleContextField, m_bundleContextField);
        }
        
        if (m_dependencyManagerField != null)
        {
            writer.put(EntryParam.dependencyManagerField, m_dependencyManagerField);
        }
        
        if (m_componentField != null)
        {
            writer.put(EntryParam.componentField, m_componentField);
        }
    }

    /**
     * Parses a ServiceDependency Annotation.
     * @param annotation the ServiceDependency Annotation.
     */
    private void parseServiceDependencyAnnotation(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.ServiceDependency);
        m_writers.add(writer);

        // service attribute
        String service = annotation.get(EntryParam.service.toString());
        if (service != null)
        {
            service = Patterns.parseClass(service, Patterns.CLASS, 1);
        }
        else
        {
            if (m_isField)
            {
                service = Patterns.parseClass(m_descriptor, Patterns.CLASS, 1);
            }
            else
            {
                service = Patterns.parseClass(m_descriptor, Patterns.BIND_CLASS, 2);
            }
        }
        writer.put(EntryParam.service, service);

        // Store this service in list of imported services.
        m_importService.add(service);
        
        // autoConfig attribute
        if (m_isField)
        {
            writer.put(EntryParam.autoConfig, m_field);
        }

        // filter attribute
        String filter = annotation.get(EntryParam.filter.toString());
        if (filter != null)
        {
            Verifier.verifyFilter(filter, 0);
            writer.put(EntryParam.filter, filter);
        }

        // defaultImpl attribute
        writer.putClass(annotation, EntryParam.defaultImpl, null);

        // added callback
        writer.putString(annotation, EntryParam.added, (!m_isField) ? m_method : null);

        // timeout parameter
        writer.putString(annotation, EntryParam.timeout, null);
        Long t = (Long) annotation.get(EntryParam.timeout.toString());
        if (t != null && t.longValue() < -1)
        {
            throw new IllegalArgumentException("Invalid timeout value " + t + " in ServiceDependency annotation from class " + m_className);
        }
        
        // required attribute (not valid if parsing a temporal service dependency)
        writer.putString(annotation, EntryParam.required, null);

        // changed callback
        writer.putString(annotation, EntryParam.changed, null);

        // removed callback
        writer.putString(annotation, EntryParam.removed, null); 
        
        // name attribute
        parseDependencyName(writer, annotation);
        
        // propagate attribute
        writer.putString(annotation, EntryParam.propagate, null);
    }

    /**
     * Parses a ConfigurationDependency annotation.
     * @param annotation the ConfigurationDependency annotation.
     */
    private void parseConfigurationDependencyAnnotation(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.ConfigurationDependency);
        m_writers.add(writer);

        // pid attribute
        writer.putString(annotation, EntryParam.pid, m_className);

        // the method on which the annotation is applied
        writer.put(EntryParam.updated, m_method);

        // propagate attribute
        writer.putString(annotation, EntryParam.propagate, null);

        // Property Meta Types
        String pid = get(annotation, EntryParam.pid.toString(), m_className);
        parseMetaTypes(annotation, pid, false);
    }

    /**
     * Parses an AspectService annotation.
     * @param annotation
     */
    private void parseAspectService(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.AspectService);
        m_writers.add(writer);

        // Register previously parsed Init/Start/Stop/Destroy/Composition annotations
        addCommonServiceParams(writer);

        // Parse service filter
        String filter = annotation.get(EntryParam.filter.toString());
        if (filter != null)
        {
            Verifier.verifyFilter(filter, 0);
            writer.put(EntryParam.filter, filter);
        }

        // Parse service aspect ranking
        Integer ranking = annotation.get(EntryParam.ranking.toString());
        writer.put(EntryParam.ranking, ranking.toString());

        // Generate Aspect Implementation
        writer.put(EntryParam.impl, m_className);

        // Parse Aspect properties.
        parseProperties(annotation, EntryParam.properties, writer);
        
        // Parse field/added/changed/removed attributes
        parseAspectOrAdapterCallbackMethods(annotation, writer);

        // Parse service interface this aspect is applying to
        Object service = annotation.get(EntryParam.service.toString());
        if (service == null)
        {
            if (m_interfaces == null)
            {
                throw new IllegalStateException("Invalid AspectService annotation: " +
                    "the service attribute has not been set and the class " + m_className
                    + " does not implement any interfaces");
            }
            if (m_interfaces.length != 1)
            {
                throw new IllegalStateException("Invalid AspectService annotation: " +
                    "the service attribute has not been set and the class " + m_className
                    + " implements more than one interface");
            }

            writer.put(EntryParam.service, m_interfaces[0]);
        }
        else
        {
            writer.putClass(annotation, EntryParam.service, null);
        }
        
        // Parse factoryMethod attribute
        writer.putString(annotation, EntryParam.factoryMethod, null);
    }

    private void parseAspectOrAdapterCallbackMethods(Annotation annotation, EntryWriter writer)
    {
        String field = annotation.get(EntryParam.field.toString());
        String added = annotation.get(EntryParam.added.toString());
        String changed = annotation.get(EntryParam.changed.toString());
        String removed = annotation.get(EntryParam.removed.toString());

        // "field" and "added/changed/removed" attributes can't be mixed
        if (field != null && (added != null || changed != null || removed != null))
        {
            throw new IllegalStateException("Annotation " + annotation + "can't applied on " + m_className
                    + " can't mix \"field\" attribute with \"added/changed/removed\" attributes");
        }
        
        // changed/removed callbacks are allowed only if added callback is defined
        if (field == null && added == null && (changed != null || removed != null))
        {
            throw new IllegalStateException("Annotation " + annotation + " applied on " + m_className
                    + " must define an \"added\" callback");
        }
        
        // Parse aspect impl field where to inject the original service.
        writer.putString(annotation, EntryParam.field, null);
        
        // Parse aspect impl callback methods.
        writer.putString(annotation, EntryParam.added, null);
        writer.putString(annotation, EntryParam.changed, null);
        writer.putString(annotation, EntryParam.removed, null);
    }

    /**
     * Parses an AspectService annotation.
     * @param annotation
     */
    private void parseAdapterService(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.AdapterService);
        m_writers.add(writer);

        // Register previously parsed Init/Start/Stop/Destroy/Composition annotations
        addCommonServiceParams(writer);

        // Generate Adapter Implementation
        writer.put(EntryParam.impl, m_className);

        // Parse adaptee filter
        String adapteeFilter = annotation.get(EntryParam.adapteeFilter.toString());
        if (adapteeFilter != null)
        {
            Verifier.verifyFilter(adapteeFilter, 0);
            writer.put(EntryParam.adapteeFilter, adapteeFilter);
        }

        // Parse the mandatory adapted service interface.
        writer.putClass(annotation, EntryParam.adapteeService, null);

        // Parse Adapter properties.
        parseProperties(annotation, EntryParam.properties, writer);

        // Parse the provided adapter service (use directly implemented interface by default).
        if (writer.putClassArray(annotation, EntryParam.provides, m_interfaces, m_exportService) == 0)
        {
            checkRegisteredUnregisteredNotPresent();
        }
        
        // Parse factoryMethod attribute
        writer.putString(annotation, EntryParam.factoryMethod, null);
        
        // Parse field/added/changed/removed attributes
        parseAspectOrAdapterCallbackMethods(annotation, writer);
    }

    /**
     * Parses a BundleAdapterService annotation.
     * @param annotation
     */
    private void parseBundleAdapterService(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.BundleAdapterService);
        m_writers.add(writer);

        // Register previously parsed Init/Start/Stop/Destroy/Composition annotations
        addCommonServiceParams(writer);

        // Generate Adapter Implementation
        writer.put(EntryParam.impl, m_className);

        // Parse bundle filter
        String filter = annotation.get(EntryParam.filter.toString());
        if (filter != null)
        {
            Verifier.verifyFilter(filter, 0);
            writer.put(EntryParam.filter, filter);
        }

        // Parse stateMask attribute
        writer.putString(annotation, EntryParam.stateMask, Integer.valueOf(
            Bundle.INSTALLED | Bundle.RESOLVED | Bundle.ACTIVE).toString());

        // Parse Adapter properties.
        parseProperties(annotation, EntryParam.properties, writer);

        // Parse the optional adapter service (use directly implemented interface by default).
        if (writer.putClassArray(annotation, EntryParam.provides, m_interfaces, m_exportService) == 0)
        {
            checkRegisteredUnregisteredNotPresent();
        }

        // Parse propagate attribute
        writer.putString(annotation, EntryParam.propagate, Boolean.FALSE.toString());
        
        // Parse factoryMethod attribute
        writer.putString(annotation, EntryParam.factoryMethod, null);
    }

    /**
     * Parses a BundleAdapterService annotation.
     * @param annotation
     */
    private void parseResourceAdapterService(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.ResourceAdapterService);
        m_writers.add(writer);

        // Register previously parsed Init/Start/Stop/Destroy/Composition annotations
        addCommonServiceParams(writer);

        // Generate Adapter Implementation
        writer.put(EntryParam.impl, m_className);

        // Parse resource filter
        String filter = annotation.get(EntryParam.filter.toString());
        if (filter != null)
        {
            Verifier.verifyFilter(filter, 0);
            writer.put(EntryParam.filter, filter);
        }

        // Parse Adapter properties.
        parseProperties(annotation, EntryParam.properties, writer);

        // Parse the provided adapter service (use directly implemented interface by default).
        if (writer.putClassArray(annotation, EntryParam.provides, m_interfaces, m_exportService) == 0)
        {
            checkRegisteredUnregisteredNotPresent();
        }

        // Parse propagate attribute
        writer.putString(annotation, EntryParam.propagate, Boolean.FALSE.toString());
        
        // Parse changed attribute
        writer.putString(annotation, EntryParam.changed, null);
    }

    /**
     * Parses a Factory Configuration Adapter annotation.
     * @param annotation
     */
    private void parseFactoryConfigurationAdapterService(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.FactoryConfigurationAdapterService);
        m_writers.add(writer);

        // Register previously parsed Init/Start/Stop/Destroy/Composition annotations
        addCommonServiceParams(writer);

        // Generate Adapter Implementation
        writer.put(EntryParam.impl, m_className);

        // Parse factory Pid
        writer.putString(annotation, EntryParam.factoryPid, m_className);

        // Parse updated callback
        writer.putString(annotation, EntryParam.updated, "updated");

        // propagate attribute
        writer.putString(annotation, EntryParam.propagate, Boolean.FALSE.toString());

        // Parse the provided adapter service (use directly implemented interface by default).
        if (writer.putClassArray(annotation, EntryParam.provides, m_interfaces, m_exportService) == 0)
        {
            checkRegisteredUnregisteredNotPresent();
        }

        // Parse Adapter properties.
        parseProperties(annotation, EntryParam.properties, writer);

        // Parse optional meta types for configuration description.
        String factoryPid = get(annotation, EntryParam.factoryPid.toString(), m_className);
        parseMetaTypes(annotation, factoryPid, true);
        
        // Parse factoryMethod attribute
        writer.putString(annotation, EntryParam.factoryMethod, null);
    }

    private void parseBundleDependencyAnnotation(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.BundleDependency);
        m_writers.add(writer);

        String filter = annotation.get(EntryParam.filter.toString());
        if (filter != null)
        {
            Verifier.verifyFilter(filter, 0);
            writer.put(EntryParam.filter, filter);
        }

        writer.putString(annotation, EntryParam.added, m_method);
        writer.putString(annotation, EntryParam.changed, null);
        writer.putString(annotation, EntryParam.removed, null);
        writer.putString(annotation, EntryParam.required, null);
        writer.putString(annotation, EntryParam.stateMask, null);
        writer.putString(annotation, EntryParam.propagate, null);
        parseDependencyName(writer, annotation);
    }

    private void parseRersourceDependencyAnnotation(Annotation annotation)
    {
        EntryWriter writer = new EntryWriter(EntryType.ResourceDependency);
        m_writers.add(writer);

        String filter = annotation.get(EntryParam.filter.toString());
        if (filter != null)
        {
            Verifier.verifyFilter(filter, 0);
            writer.put(EntryParam.filter, filter);
        }
        
        if (m_isField)
        {
            writer.put(EntryParam.autoConfig, m_field);
        }

        writer.putString(annotation, EntryParam.added, (!m_isField) ? m_method : null);
        writer.putString(annotation, EntryParam.changed, null);
        writer.putString(annotation, EntryParam.removed, null);
        writer.putString(annotation, EntryParam.required, null);
        writer.putString(annotation, EntryParam.propagate, null);
        writer.putString(annotation, EntryParam.factoryMethod, null);
        parseDependencyName(writer, annotation);
    }

    /**
     * Parse the name of a given dependency.
     * @param writer The writer where to write the dependency name
     * @param annotation the dependency to be parsed
     */
    private void parseDependencyName(EntryWriter writer, Annotation annotation)
    {
        String name = annotation.get(EntryParam.name.toString());
        if (name != null) 
        {
            if(! m_dependencyNames.add(name))
            {
                throw new IllegalArgumentException("Duplicate dependency name " + name + " in Dependency " + annotation + " from class " + m_className);
            }
            writer.put(EntryParam.name, name);
        }
    }
    
    private void parseLifecycleAnnotation(Annotation annotation)
    {
        Patterns.parseField(m_field, m_descriptor, Patterns.RUNNABLE);
        if ("true".equals(get(annotation,EntryParam.start.name(), "true")))
        {
            if (m_starter != null) {
                throw new IllegalStateException("Lifecycle annotation already defined on field " + 
                                                m_starter + " in class " + m_className);
            }
            m_starter = m_field;
        } else {
            if (m_stopper != null) {
                throw new IllegalStateException("Lifecycle annotation already defined on field " + 
                                                m_stopper + " in class " + m_className);
            }
            m_stopper = m_field;
        }
    }

    /**
     * Parse optional meta types annotation attributes
     * @param annotation
     */
    private void parseMetaTypes(Annotation annotation, String pid, boolean factory)
    {
        if (annotation.get("metadata") != null)
        {
            String propertiesHeading = annotation.get("heading");
            String propertiesDesc = annotation.get("description");

            MetaType.OCD ocd = new MetaType.OCD(pid, propertiesHeading, propertiesDesc);
            for (Object p: (Object[]) annotation.get("metadata"))
            {
                Annotation property = (Annotation) p;
                String heading = property.get("heading");
                String id = property.get("id");
                String type = (String) property.get("type");
                type = (type != null) ? Patterns.parseClass(type, Patterns.CLASS, 1) : null;
                Object[] defaults = (Object[]) property.get("defaults");
                String description = property.get("description");
                Integer cardinality = property.get("cardinality");
                Boolean required = property.get("required");

                MetaType.AD ad = new MetaType.AD(id, type, defaults, heading, description,
                    cardinality, required);

                Object[] optionLabels = property.get("optionLabels");
                Object[] optionValues = property.get("optionValues");

                if (optionLabels == null
                    && optionValues != null
                    ||
                    optionLabels != null
                    && optionValues == null
                    ||
                    (optionLabels != null && optionValues != null && optionLabels.length != optionValues.length))
                {
                    throw new IllegalArgumentException("invalid option labels/values specified for property "
                        + id +
                        " in PropertyMetadata annotation from class " + m_className);
                }

                if (optionValues != null)
                {
                    for (int i = 0; i < optionValues.length; i++)
                    {
                        ad.add(new MetaType.Option(optionValues[i].toString(), optionLabels[i].toString()));
                    }
                }

                ocd.add(ad);
            }

            m_metaType.add(ocd);
            MetaType.Designate designate = new MetaType.Designate(pid, factory);
            m_metaType.add(designate);
            m_logger.info("Parsed MetaType Properties from class " + m_className);
        }
    }

    /**
     * Parses a Property annotation (which represents a list of key-value pair).
     * @param annotation the annotation where the Param annotation is defined
     * @param attribute the attribute name which is of Param type
     * @param writer the object where the parsed attributes are written
     */
    private void parseProperties(Annotation annotation, EntryParam attribute, EntryWriter writer)
    {
        Object[] parameters = annotation.get(attribute.toString());
        Map<String, Object> properties = new HashMap<String, Object>();
        if (parameters != null)
        {
            for (Object p: parameters)
            {
                Annotation a = (Annotation) p;
                String name = (String) a.get("name");
                String value = (String) a.get("value");
                if (value != null)
                {
                    properties.put(name, value);
                }
                else
                {
                    Object[] values = a.get("values");
                    if (values != null)
                    {
                        // the values is an Object array of actual strings, and we must convert it into a String array.
                        properties.put(name, Arrays.asList(values).toArray(new String[values.length]));
                    }
                    else
                    {
                        throw new IllegalArgumentException("Invalid Property attribyte \"" + attribute
                            + " from annotation " + annotation + " in class " + m_className);
                    }
                }
            }
            writer.putProperties(attribute, properties);
        }
    }
    
    /**
     * Parse Inject annotation, used to inject some special classes in some fields
     * (BundleContext/DependencyManager etc ...)
     * @param annotation the Inject annotation
     */
    private void parseInject(Annotation annotation)
    {      
        if (Patterns.BUNDLE_CONTEXT.matcher(m_descriptor).matches())
        {
            m_bundleContextField = m_field;
        }
        else if (Patterns.DEPENDENCY_MANAGER.matcher(m_descriptor).matches())
        {
            m_dependencyManagerField = m_field;
        }
        else if (Patterns.COMPONENT.matcher(m_descriptor).matches())
        {
            m_componentField = m_field;
        }
        else
        {
            throw new IllegalArgumentException("@Inject annotation can't be applied on the field \"" + m_field
                                               + "\" in class " + m_className);
        }
    }
    
    /**
     * Checks if the class is annotated with some given annotations. Notice that the Service
     * is always parsed at end of parsing, so, we have to check the last element of our m_writers
     * List.
     * @return true if one of the provided annotations has been found from the parsed class.
     */
    private void checkServiceDeclared(EntryType... types)
    {
        boolean ok = false;
        if (m_writers.size() > 0)
        {
            for (EntryType type: types)
            {
                if (m_writers.get(m_writers.size() - 1).getEntryType() == type)
                {
                    ok = true;
                    break;
                }
            }
        }

        if (!ok)
        {
            throw new IllegalStateException(
                ": the class must be annotated with either one of the following types: "
                    + Arrays.toString(types));
        }
    }

    /**
     * This method checks if the @Registered and/or @Unregistered annotations have been defined
     * while they should not, because the component does not provide a service.
     */
    private void checkRegisteredUnregisteredNotPresent()
    {
        if (m_registeredMethod != null)
        {
            throw new IllegalStateException("@Registered annotation can't be used on a Component " +
                                            " which does not provide a service (class=" + m_className + ")");

        }
        
        if (m_unregisteredMethod != null)
        {
            throw new IllegalStateException("@Unregistered annotation can't be used on a Component " +
                                            " which does not provide a service (class=" + m_className + ")");

        }
    }

    /**
     * Get an annotation attribute, and return a default value if its not present.
     * @param <T> the type of the variable which is assigned to the return value of this method.
     * @param annotation The annotation we are parsing
     * @param name the attribute name to get from the annotation
     * @param defaultValue the default value to return if the attribute is not found in the annotation
     * @return the annotation attribute value, or the defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    private <T> T get(Annotation annotation, String name, T defaultValue)
    {
        T value = (T) annotation.get(name);
        return value != null ? value : defaultValue;
    }
}