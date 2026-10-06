// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.xml.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;

final class ImplementationClassCache {
  private static final Comparator<Class<?>> CLASS_COMPARATOR = (o1, o2) -> {
    if (o1.isAssignableFrom(o2)) return 1;
    if (o2.isAssignableFrom(o1)) return -1;
    if (o1.equals(o2)) return 0;
    throw new AssertionError("Incompatible implementation classes: " + o1 + " & " + o2);
  };

  // a set, because the listener and the constructor loop can both add the same extension
  private final MultiMap<String, DomImplementationClassEP> myImplementationClasses = MultiMap.createLinkedSet();

  private final SofterCache<Class<?>, Class<?>> myCache = new SofterCache<>(concreteInterface -> {
    TreeSet<Class<?>> set = new TreeSet<>(CLASS_COMPARATOR);
    findImplementationClassDFS(concreteInterface, set);
    if (!set.isEmpty()) {
      return set.first();
    }

    return concreteInterface;
  });

  ImplementationClassCache(ExtensionPointName<DomImplementationClassEP> epName) {
    if (ApplicationManager.getApplication().isDisposed()) {
      return;
    }

    ExtensionPoint<DomImplementationClassEP> point = epName.getPoint();
    ExtensionPointListener<DomImplementationClassEP> listener = new ExtensionPointListener<>() {
      @Override
      public void extensionAdded(@NotNull DomImplementationClassEP ep, @NotNull PluginDescriptor pluginDescriptor) {
        myImplementationClasses.putValue(ep.interfaceName, ep);
        clearCache();
      }

      @Override
      public void extensionRemoved(@NotNull DomImplementationClassEP ep, @NotNull PluginDescriptor pluginDescriptor) {
        myImplementationClasses.remove(ep.interfaceName, ep);
        clearCache();
      }
    };
    // do not use `invokeForLoadedExtensions = true`: it swallows a cancellation and leaves the cache incomplete
    point.addExtensionPointListener(listener, false, null);
    try {
      for (DomImplementationClassEP ep : point.getExtensionList()) {
        myImplementationClasses.putValue(ep.interfaceName, ep);
      }
    }
    catch (Throwable e) {
      point.removeExtensionPointListener(listener);
      throw e;
    }
  }

  private void findImplementationClassDFS(@NotNull Class<?> concreteInterface, SortedSet<? super Class<?>> results) {
    Collection<DomImplementationClassEP> values = myImplementationClasses.get(concreteInterface.getName());
    for (DomImplementationClassEP value : values) {
      if (value.getInterfaceClass() == concreteInterface) {
        results.add(value.getImplementationClass());
        return;
      }
    }
    for (Class<?> aClass1 : concreteInterface.getInterfaces()) {
      findImplementationClassDFS(aClass1, results);
    }
  }

  void registerImplementation(Class<?> domElementClass, Class<?> implementationClass, @Nullable Disposable parentDisposable) {
    final DomImplementationClassEP ep = new DomImplementationClassEP() {
      @Override
      public Class<?> getInterfaceClass() {
        return domElementClass;
      }

      @Override
      public Class<?> getImplementationClass() {
        return implementationClass;
      }
    };
    myImplementationClasses.putValue(domElementClass.getName(), ep);
    if (parentDisposable != null) {
      Disposer.register(parentDisposable, new Disposable() {
        @Override
        public void dispose() {
          myImplementationClasses.remove(domElementClass.getName());
        }
      });
    }
    myCache.clearCache();
  }

  @Nullable Class<?> get(Class<?> key) {
    Class<?> impl = myCache.getCachedValue(key);
    return impl == key ? null : impl;
  }

  void clearCache() {
    myCache.clearCache();
  }
}
