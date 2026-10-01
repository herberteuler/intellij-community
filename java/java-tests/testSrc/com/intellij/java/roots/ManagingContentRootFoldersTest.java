// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.roots;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ContentFolder;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.JavaProjectTestCase;
import com.intellij.testFramework.PsiTestUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class ManagingContentRootFoldersTest extends JavaProjectTestCase {
  private VirtualFile root;
  private ContentEntry entry;
  private ModifiableRootModel myModel;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    ApplicationManager.getApplication().runWriteAction(() -> {
      initContentRoot();
      initModifiableModel();
    });
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (myModel != null && myModel.isWritable()) {
        myModel.dispose();
      }
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      myModel = null;
      entry = null;
      super.tearDown();
    }
  }

  private void initContentRoot() {
    try {
      File dir = createTempDirectory();
      root = StandardFileSystems.local().refreshAndFindFileByPath(dir.getAbsolutePath());
      PsiTestUtil.addContentRoot(myModule, root);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void initModifiableModel() {
    myModel = ModuleRootManager.getInstance(myModule).getModifiableModel();
    for (ContentEntry e : myModel.getContentEntries()) {
      if (Comparing.equal(e.getFile(), root)) entry = e;
    }
  }

  public void testCreationOfSourceFolderWithFile() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();

    ContentFolder f = entry.addSourceFolder(dir, false);
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());

    delete(dir);
    assertNull(f.getFile());
    assertEquals(url, f.getUrl());

    dir = createSrc();
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }


  public void testCreationOfSourceFolderWithUrl() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();
    delete(dir);

    ContentFolder f = entry.addSourceFolder(url, false);
    assertNull(f.getFile());
    assertEquals(url, f.getUrl());

    dir = createSrc();
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }

  public void testCreationOfSourceFolderWithUrlWhenFileExists() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();

    ContentFolder f = entry.addSourceFolder(url, false);
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }

  public void testCreationOfExcludedFolderWithFile() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();

    ContentFolder f = entry.addExcludeFolder(dir);
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());

    delete(dir);
    assertNull(f.getFile());
    assertEquals(url, f.getUrl());

    dir = createSrc();
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }

  @NotNull
  private VirtualFile createSrc() {
    return createChildDirectory(root, "src");
  }

  public void testCreationOfExcludedFolderWithUrl() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();
    delete(dir);

    ContentFolder f = entry.addExcludeFolder(url);
    assertNull(f.getFile());
    assertEquals(url, f.getUrl());

    dir = createSrc();
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }

  public void testCreationOfExcludedFolderWithUrlWhenFileExists() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();

    ContentFolder f = entry.addExcludeFolder(url);
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }

  public void testAddingExcludeWithoutSlash() {
    VirtualFile dir = createSrc();
    String url = dir.getUrl();

    Ref<ContentEntry> ref = Ref.create();
    ModuleRootModificationUtil.updateModel(myModule, model -> {
      var entry = model.addContentEntry(url + "/");
      ref.set(entry);
    });

    ContentFolder f = ref.get().addExcludeFolder(url);
    assertEquals(dir, f.getFile());
    assertEquals(url, f.getUrl());
  }
}
