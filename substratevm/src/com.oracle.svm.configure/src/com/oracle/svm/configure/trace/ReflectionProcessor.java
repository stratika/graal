/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.configure.trace;

import static com.oracle.svm.configure.trace.LazyValueUtils.lazyValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.graalvm.collections.Pair;
import org.graalvm.compiler.phases.common.LazyValue;
import org.graalvm.nativeimage.impl.ConfigurationCondition;

import com.oracle.svm.configure.config.ConfigurationMemberKind;
import com.oracle.svm.configure.config.ConfigurationMethod;
import com.oracle.svm.configure.config.ProxyConfiguration;
import com.oracle.svm.configure.config.ResourceConfiguration;
import com.oracle.svm.configure.config.SignatureUtil;
import com.oracle.svm.configure.config.TypeConfiguration;

import jdk.vm.ci.meta.MetaUtil;

class ReflectionProcessor extends AbstractProcessor {
    private final AccessAdvisor advisor;
    private final TypeConfiguration configuration;
    private final ProxyConfiguration proxyConfiguration;
    private final ResourceConfiguration resourceConfiguration;

    ReflectionProcessor(AccessAdvisor advisor, TypeConfiguration typeConfiguration, ProxyConfiguration proxyConfiguration, ResourceConfiguration resourceConfiguration) {
        this.advisor = advisor;
        this.configuration = typeConfiguration;
        this.proxyConfiguration = proxyConfiguration;
        this.resourceConfiguration = resourceConfiguration;
    }

    public TypeConfiguration getConfiguration() {
        return configuration;
    }

    public ProxyConfiguration getProxyConfiguration() {
        return proxyConfiguration;
    }

    public ResourceConfiguration getResourceConfiguration() {
        return resourceConfiguration;
    }

    @Override
    @SuppressWarnings("fallthrough")
    public void processEntry(Map<String, ?> entry) {
        boolean invalidResult = Boolean.FALSE.equals(entry.get("result"));
        ConfigurationCondition condition = ConfigurationCondition.objectReachable();
        if (invalidResult) {
            return;
        }
        String function = (String) entry.get("function");
        List<?> args = (List<?>) entry.get("args");
        switch (function) {
            // These are called via java.lang.Class or via the class loader hierarchy, so we would
            // always filter based on the caller class.
            case "getResource":
            case "getResourceAsStream":
            case "getSystemResource":
            case "getSystemResourceAsStream":
            case "getResources":
            case "getSystemResources":
                String literal = singleElement(args);
                String regex = Pattern.quote(literal);
                resourceConfiguration.addResourcePattern(regex);
                return;
        }
        String callerClass = (String) entry.get("caller_class");
        boolean isLoadClass = function.equals("loadClass");
        if (isLoadClass || function.equals("forName") || function.equals("findClass")) {
            String name = singleElement(args);
            if (isLoadClass) { // different array syntax
                name = MetaUtil.internalNameToJava(MetaUtil.toInternalName(name), true, true);
            }
            if (!advisor.shouldIgnore(lazyValue(name), lazyValue(callerClass)) &&
                            !(isLoadClass && advisor.shouldIgnoreLoadClass(lazyValue(name), lazyValue(callerClass)))) {
                configuration.getOrCreateType(condition, name);
            }
            return;
        } else if (function.equals("methodTypeDescriptor")) {
            List<String> typeNames = singleElement(args);
            for (String type : typeNames) {
                if (!advisor.shouldIgnore(lazyValue(type), lazyValue(callerClass))) {
                    configuration.getOrCreateType(condition, type);
                }
            }
        }
        String clazz = (String) entry.get("class");
        if (advisor.shouldIgnore(lazyValue(clazz), lazyValue(callerClass))) {
            return;
        }
        ConfigurationMemberKind memberKind = ConfigurationMemberKind.PUBLIC;
        String clazzOrDeclaringClass = entry.containsKey("declaring_class") ? (String) entry.get("declaring_class") : clazz;
        switch (function) {
            case "getDeclaredFields": {
                configuration.getOrCreateType(condition, clazz).setAllDeclaredFields();
                break;
            }
            case "getFields": {
                configuration.getOrCreateType(condition, clazz).setAllPublicFields();
                break;
            }

            case "getDeclaredMethods": {
                configuration.getOrCreateType(condition, clazz).setAllDeclaredMethods();
                break;
            }
            case "asInterfaceInstance":
            case "getMethods": {
                configuration.getOrCreateType(condition, clazz).setAllPublicMethods();
                break;
            }

            case "getDeclaredConstructors": {
                configuration.getOrCreateType(condition, clazz).setAllDeclaredConstructors();
                break;
            }
            case "getConstructors": {
                configuration.getOrCreateType(condition, clazz).setAllPublicConstructors();
                break;
            }

            case "getDeclaredClasses": {
                configuration.getOrCreateType(condition, clazz).setAllDeclaredClasses();
                break;
            }
            case "getClasses": {
                configuration.getOrCreateType(condition, clazz).setAllPublicClasses();
                break;
            }

            case "objectFieldOffset":
            case "findFieldHandle":
            case "unreflectField":
            case "getDeclaredField":
                memberKind = "findFieldHandle".equals(function) ? ConfigurationMemberKind.PRESENT : ConfigurationMemberKind.DECLARED;
                // fall through
            case "getField": {
                configuration.getOrCreateType(condition, clazzOrDeclaringClass).addField(singleElement(args), memberKind, false);
                if (!clazzOrDeclaringClass.equals(clazz)) {
                    configuration.getOrCreateType(condition, clazz);
                }
                break;
            }

            case "getDeclaredMethod":
            case "findMethodHandle":
                memberKind = "findMethodHandle".equals(function) ? ConfigurationMemberKind.PRESENT : ConfigurationMemberKind.DECLARED;
                // fall through
            case "getMethod": {
                expectSize(args, 2);
                String name = (String) args.get(0);
                List<?> parameterTypes = (List<?>) args.get(1);
                if (parameterTypes == null) { // tolerated and equivalent to no parameter types
                    parameterTypes = Collections.emptyList();
                }
                configuration.getOrCreateType(condition, clazzOrDeclaringClass).addMethod(name, SignatureUtil.toInternalSignature(parameterTypes), memberKind);
                if (!clazzOrDeclaringClass.equals(clazz)) {
                    configuration.getOrCreateType(condition, clazz);
                }
                break;
            }

            case "getDeclaredConstructor":
            case "findConstructorHandle":
                memberKind = "findConstructorHandle".equals(function) ? ConfigurationMemberKind.PRESENT : ConfigurationMemberKind.DECLARED;
                // fall through
            case "getConstructor": {
                List<String> parameterTypes = singleElement(args);
                if (parameterTypes == null) { // tolerated and equivalent to no parameter types
                    parameterTypes = Collections.emptyList();
                }
                String signature = SignatureUtil.toInternalSignature(parameterTypes);
                assert clazz.equals(clazzOrDeclaringClass) : "Constructor can only be accessed via declaring class";
                configuration.getOrCreateType(condition, clazzOrDeclaringClass).addMethod(ConfigurationMethod.CONSTRUCTOR_NAME, signature, memberKind);
                break;
            }

            case "getProxyClass": {
                expectSize(args, 2);
                addDynamicProxy((List<?>) args.get(1), lazyValue(callerClass));
                break;
            }
            case "newProxyInstance": {
                expectSize(args, 3);
                addDynamicProxy((List<?>) args.get(1), lazyValue(callerClass));
                break;
            }
            case "newMethodHandleProxyInstance": {
                expectSize(args, 1);
                addDynamicProxyUnchecked((List<?>) args.get(0), Collections.singletonList("sun.invoke.WrapperInstance"), lazyValue(callerClass));
                break;
            }

            case "getEnclosingConstructor":
            case "getEnclosingMethod": {
                String result = (String) entry.get("result");
                addFullyQualifiedDeclaredMethod(result);
                break;
            }

            case "newInstance": {
                if (clazz.equals("java.lang.reflect.Array")) { // reflective array instantiation
                    configuration.getOrCreateType(condition, (String) args.get(0));
                } else {
                    configuration.getOrCreateType(condition, clazz).addMethod(ConfigurationMethod.CONSTRUCTOR_NAME, "()V", ConfigurationMemberKind.DECLARED);
                }
                break;
            }

            case "getBundleImplJDK8OrEarlier": {
                expectSize(args, 5);
                String baseName = (String) args.get(0);
                @SuppressWarnings("unchecked")
                List<Pair<String, String>> bundleInfo = (List<Pair<String, String>>) args.get(4);
                resourceConfiguration.addBundle(bundleInfo, baseName);
                break;
            }
            case "getBundleImplJDK11OrLater": {
                expectSize(args, 6);
                String baseName = (String) args.get(2);
                @SuppressWarnings("unchecked")
                List<Pair<String, String>> bundleInfo = (List<Pair<String, String>>) args.get(5);
                resourceConfiguration.addBundle(bundleInfo, baseName);
                break;
            }
            default:
                System.err.println("Unsupported reflection method: " + function);
        }
    }

    private void addFullyQualifiedDeclaredMethod(String descriptor) {
        int sigbegin = descriptor.indexOf('(');
        int classend = descriptor.lastIndexOf('.', sigbegin - 1);
        String qualifiedClass = descriptor.substring(0, classend);
        String methodName = descriptor.substring(classend + 1, sigbegin);
        String signature = descriptor.substring(sigbegin);
        configuration.getOrCreateType(ConfigurationCondition.objectReachable(), qualifiedClass).addMethod(methodName, signature, ConfigurationMemberKind.DECLARED);
    }

    private void addDynamicProxy(List<?> interfaceList, LazyValue<String> callerClass) {
        @SuppressWarnings("unchecked")
        List<String> interfaces = (List<String>) interfaceList;
        for (String iface : interfaces) {
            if (advisor.shouldIgnore(lazyValue(iface), callerClass)) {
                return;
            }
        }
        proxyConfiguration.add(interfaces);
    }

    private void addDynamicProxyUnchecked(List<?> checkedInterfaceList, List<?> uncheckedInterfaceList, LazyValue<String> callerClass) {
        @SuppressWarnings("unchecked")
        List<String> checkedInterfaces = (List<String>) checkedInterfaceList;
        for (String iface : checkedInterfaces) {
            if (advisor.shouldIgnore(lazyValue(iface), callerClass)) {
                return;
            }
        }
        @SuppressWarnings("unchecked")
        List<String> uncheckedInterfaces = (List<String>) uncheckedInterfaceList;

        List<String> interfaces = new ArrayList<>();
        interfaces.addAll(checkedInterfaces);
        interfaces.addAll(uncheckedInterfaces);
        proxyConfiguration.add(interfaces);
    }
}
