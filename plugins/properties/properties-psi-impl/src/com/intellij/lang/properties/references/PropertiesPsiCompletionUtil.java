// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.lang.properties.references;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.PropertiesHighlighter;
import com.intellij.lang.properties.PropertiesReferenceManager;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PropertiesPsiCompletionUtil {
  /**
   * Returns the foreground color for a property value, or null when no color scheme is available.
   */
  public static @Nullable Color getPropertyValueForeground() {
    var manager = EditorColorsManager.getInstance();
    if (manager == null) {
      return null;
    }
    var attributes = manager.getGlobalScheme()
      .getAttributes(PropertiesHighlighter.PropertiesComponent.PROPERTY_VALUE.getTextAttributesKey());
    return attributes == null ? null : attributes.getForegroundColor();
  }

  public static void addVariantsFromFile(PropertyReferenceBase propertyReference,
                                         final PropertiesFile propertiesFile,
                                         final Set<Object> variants) {
    if (propertiesFile == null) return;
    VirtualFile virtualFile = propertiesFile.getVirtualFile();
    if (virtualFile == null || !ProjectRootManager.getInstance(propertiesFile.getProject()).getFileIndex().isInContent(virtualFile)) return;
    List<? extends IProperty> properties = propertiesFile.getProperties();
    for (IProperty property : properties) {
      propertyReference.addKey(property, variants);
    }
  }

  public static Set<Object> getPropertiesKeys(final PropertyReferenceBase propertyReference) {
    final Set<Object> variants = new ObjectOpenCustomHashSet<>(new Hash.Strategy<>() {
      @Override
      public int hashCode(@Nullable Object object) {
        if (object instanceof IProperty) {
          String key = ((IProperty)object).getKey();
          return key == null ? 0 : key.hashCode();
        }
        else {
          return 0;
        }
      }

      @Override
      public boolean equals(@Nullable Object o1, @Nullable Object o2) {
        if (o1 == o2) {
          return true;
        }
        return o1 instanceof IProperty && o2 instanceof IProperty &&
               Objects.equals(((IProperty)o1).getKey(), ((IProperty)o2).getKey());
      }
    });
    List<PropertiesFile> propertiesFileList = propertyReference.getPropertiesFiles();
    if (propertiesFileList == null) {
      PropertiesReferenceManager
        .getInstance(propertyReference.getElement().getProject()).processAllPropertiesFiles((baseName, propertiesFile) -> {
          addVariantsFromFile(propertyReference, propertiesFile, variants);
          return true;
        });
    }
    else {
      for (PropertiesFile propFile : propertiesFileList) {
        addVariantsFromFile(propertyReference, propFile, variants);
      }
    }
    return variants;
  }
}
