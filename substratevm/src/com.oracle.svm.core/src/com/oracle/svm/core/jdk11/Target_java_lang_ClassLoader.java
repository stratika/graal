/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.jdk11;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.util.LazyFinalReference;

@SuppressWarnings({"unused"})
@TargetClass(value = ClassLoader.class)
public final class Target_java_lang_ClassLoader {

    /**
     * All ClassLoaderValue are reset at run time for now. See also
     * {@link Target_jdk_internal_loader_BootLoader#CLASS_LOADER_VALUE_MAP} for resetting of the
     * boot class loader.
     */
    @Alias @RecomputeFieldValue(kind = RecomputeFieldValue.Kind.NewInstance, declClass = ConcurrentHashMap.class)//
    ConcurrentHashMap<?, ?> classLoaderValueMap;

    @Alias
    native Stream<Package> packages();

    @SuppressWarnings("static-method")
    @Substitute
    public Target_java_lang_Module getUnnamedModule() {
        return ClassLoaderUtil.unnamedModuleReference.get();
    }

    @Alias
    protected native Class<?> findLoadedClass(String name);

}

final class ClassLoaderUtil {

    public static final LazyFinalReference<Target_java_lang_Module> unnamedModuleReference = new LazyFinalReference<>(Target_java_lang_Module::new);
}
