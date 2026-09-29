// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.mcp.imports;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.daemon.impl.quickfix.ImportClassFix;
import com.intellij.codeInsight.daemon.impl.quickfix.StaticImportConstantFix;
import com.intellij.codeInsight.daemon.impl.quickfix.StaticImportMemberFix;
import com.intellij.codeInsight.daemon.impl.quickfix.StaticImportMethodFix;
import com.intellij.codeInsight.intention.impl.AddSingleMemberStaticImportAction;
import com.intellij.mcpserver.imports.McpImportCandidate;
import com.intellij.mcpserver.imports.McpImportChange;
import com.intellij.mcpserver.imports.McpMissingImport;
import com.intellij.mcpserver.imports.McpMissingImportProvider;
import com.intellij.modcommand.ModCommand;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPackageStatement;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleSettings;
import com.intellij.psi.codeStyle.PackageEntryTable;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Adds a missing Java import.
 */
public final class JavaMissingImportProvider implements McpMissingImportProvider {
  @Override
  public @Nullable McpImportChange addMissingImports(@NotNull PsiFile file, boolean takeBestCandidate, boolean optimize) {
    if (!(file instanceof PsiJavaFile javaFile)) return null;
    Project project = javaFile.getProject();
    CodeStyleSettings projectSettings = CodeStyle.getCustomSettings(javaFile, JavaCodeStyleSettings.class).getContainer();
    Map<String, FoundName> foundNames = new LinkedHashMap<>();
    Set<String> remainingNames = new HashSet<>();
    ModCommand command = ModCommand.psiUpdate(javaFile, copy -> {
      // psiUpdate runs its consumer right away and on this thread, so the local settings cover it.
      CodeStyle.runWithLocalSettings(project, projectSettings, settings -> {
        keepEveryImportExplicit(settings);
        importAll(copy, takeBestCandidate, foundNames);
      });
      if (optimize) optimizeImports(copy);
      for (PsiJavaCodeReferenceElement reference : unresolvedReferences(copy)) {
        remainingNames.add(reference.getReferenceName());
      }
    });

    List<McpMissingImport> missingImports = new ArrayList<>();
    foundNames.forEach((name, found) -> missingImports.add(new McpMissingImport(name, found.offset(), found.candidates())));
    return new McpImportChange(missingImports, command, remainingNames);
  }

  /** A name the first pass met, with the offset of its first reference and its candidates. */
  private record FoundName(int offset, @NotNull List<McpImportCandidate> candidates) {
  }

  /** The candidates of one reference, next to the declarations that an import binds to. */
  private record NameCandidates(@NotNull List<McpImportCandidate> candidates, @NotNull List<PsiMember> targets) {
    static final NameCandidates NONE = new NameCandidates(List.of(), List.of());
  }

  private static void optimizeImports(@NotNull PsiJavaFile file) {
    JavaCodeStyleManager.getInstance(file.getProject()).optimizeImports(file);
  }

  private static @NotNull Set<String> importsOf(@NotNull PsiJavaFile javaFile) {
    PsiImportList importList = javaFile.getImportList();
    if (importList == null) return Set.of();

    Set<String> imports = new HashSet<>();
    for (PsiImportStatementBase statement : importList.getAllImportStatements()) {
      PsiJavaCodeReferenceElement reference = statement.getImportReference();
      if (reference == null) continue;
      String qualifiedName = reference.getQualifiedName();
      imports.add(statement.isOnDemand() ? qualifiedName + ".*" : qualifiedName);
    }
    return imports;
  }

  private static void importAll(@NotNull PsiJavaFile file, boolean takeBestCandidate, @NotNull Map<String, FoundName> foundNames) {
    Project project = file.getProject();
    SmartPointerManager pointerManager = SmartPointerManager.getInstance(project);
    Set<String> importsBefore;
    do {
      importsBefore = importsOf(file);
      List<SmartPsiElementPointer<PsiJavaCodeReferenceElement>> pending = new ArrayList<>();
      // The offsets are taken before the pass changes anything, so the first pass keeps the offsets of the file.
      List<Integer> offsets = new ArrayList<>();
      for (PsiJavaCodeReferenceElement reference : unresolvedReferences(file)) {
        pending.add(pointerManager.createSmartPsiElementPointer(reference));
        offsets.add(reference.getTextRange().getStartOffset());
      }
      Set<String> undecidedNames = new HashSet<>();
      for (int i = 0; i < pending.size(); i++) {
        PsiJavaCodeReferenceElement reference = pending.get(i).getElement();
        if (reference == null || reference.resolve() != null) continue;
        String name = reference.getReferenceName();
        if (name == null || undecidedNames.contains(name)) continue;

        NameCandidates candidates = candidatesOf(project, file, reference);
        foundNames.putIfAbsent(name, new FoundName(offsets.get(i), candidates.candidates()));
        if (candidates.targets().isEmpty() || candidates.targets().size() > 1 && !takeBestCandidate) {
          undecidedNames.add(name);
          continue;
        }
        bind(file, reference, candidates.targets().getFirst());
      }
    }
    while (!importsBefore.equals(importsOf(file)));
  }

  /**
   * Collects the candidates of one unresolved reference.
   * <p>
   * A call takes a static method. Any other reference takes a class first, because a class import is
   * the common case, and a static field only when no class matches the name.
   */
  private static @NotNull NameCandidates candidatesOf(@NotNull Project project,
                                                      @NotNull PsiJavaFile file,
                                                      @NotNull PsiJavaCodeReferenceElement reference) {
    PsiMethodCallExpression call = callOf(reference);
    if (call == null) {
      // The fix narrows by how the code uses the type too, a filter that the editor does not run yet.
      ImportClassFix classFix = new ImportClassFix(reference, true);
      if (classFix.isAvailable(project, file)) {
        NameCandidates classes = nameCandidates(classFix.getClassesToImport(), JavaMissingImportProvider::toClassCandidate);
        if (!classes.targets().isEmpty()) return classes;
      }
    }
    StaticImportMemberFix<? extends PsiMember, ?> staticFix = staticFixOf(file, reference, call);
    if (!staticFix.isAvailable(project, file)) return NameCandidates.NONE;
    return nameCandidates(staticFix.getHintCandidates(), JavaMissingImportProvider::toStaticCandidate);
  }

  private static <T extends PsiMember> @NotNull NameCandidates nameCandidates(@NotNull List<? extends T> members,
                                                                              @NotNull Function<? super T, @Nullable McpImportCandidate> toCandidate) {
    List<McpImportCandidate> candidates = new ArrayList<>();
    List<PsiMember> targets = new ArrayList<>();
    for (T member : members) {
      McpImportCandidate candidate = toCandidate.apply(member);
      if (candidate == null) continue;
      candidates.add(candidate);
      targets.add(member);
    }
    return new NameCandidates(candidates, targets);
  }

  private static void bind(@NotNull PsiJavaFile file, @NotNull PsiJavaCodeReferenceElement reference, @NotNull PsiMember target) {
    if (target instanceof PsiClass psiClass) {
      reference.bindToElement(psiClass);
    }
    else {
      AddSingleMemberStaticImportAction.bindAllClassRefs(file, target, target.getName(), target.getContainingClass());
    }
  }

  private static @NotNull Iterable<PsiJavaCodeReferenceElement> unresolvedReferences(@NotNull PsiJavaFile file) {
    return SyntaxTraverser.psiTraverser(file)
      .filter(PsiJavaCodeReferenceElement.class)
      .filter(reference -> reference.getReferenceName() != null && canTakeImport(reference) && reference.resolve() == null);
  }

  /**
   * Raises the code style counters that turn a group of imports into an import on demand.
   * <p>
   * {@code ImportHelper} collapses the group and then adds an explicit import for each name that the
   * wildcard would shadow. It resolves that name in a file that still has missing imports, so it can
   * keep the wrong class. A static member counts on its own path, which is why both counters go up.
   * <p>
   * One case stays: when the package of the file holds a class of the same short name,
   * {@code ImportHelper} takes the import on demand whatever the counters say.
   */
  private static void keepEveryImportExplicit(@NotNull CodeStyleSettings settings) {
    JavaCodeStyleSettings javaSettings = settings.getCustomSettings(JavaCodeStyleSettings.class);
    javaSettings.USE_SINGLE_CLASS_IMPORTS = true;
    // Without it, the fix binds an inner class as Outer.Inner and so rewrites the code, not the imports.
    javaSettings.INSERT_INNER_CLASS_IMPORTS = true;
    javaSettings.CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND = Integer.MAX_VALUE;
    javaSettings.NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND = Integer.MAX_VALUE;
    javaSettings.PACKAGES_TO_USE_IMPORT_ON_DEMAND = new PackageEntryTable();
  }

  /** A call takes a static method, any other reference a static field. */
  private static @NotNull StaticImportMemberFix<? extends PsiMember, ?> staticFixOf(@NotNull PsiJavaFile file,
                                                                                    @NotNull PsiJavaCodeReferenceElement reference,
                                                                                    @Nullable PsiMethodCallExpression call) {
    return call != null ? new StaticImportMethodFix(file, call) : new StaticImportConstantFix(file, reference);
  }


  private static @Nullable PsiMethodCallExpression callOf(@NotNull PsiJavaCodeReferenceElement reference) {
    if (!(reference instanceof PsiReferenceExpression referenceExpression)) return null;
    if (!(referenceExpression.getParent() instanceof PsiMethodCallExpression call)) return null;
    return call.getMethodExpression() == referenceExpression ? call : null;
  }

  /**
   * Tells whether an import can fix {@code reference}.
   * <p>
   * A qualified reference already names its package. An import statement, a package statement and a
   * doc comment need a fix of their own.
   */
  private static boolean canTakeImport(@NotNull PsiJavaCodeReferenceElement reference) {
    if (reference.getQualifier() != null) return false;
    if (PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase.class, false) != null) return false;
    if (PsiTreeUtil.getParentOfType(reference, PsiPackageStatement.class, false) != null) return false;
    return PsiTreeUtil.getParentOfType(reference, PsiDocComment.class, false) == null;
  }

  private static @Nullable McpImportCandidate toClassCandidate(@NotNull PsiClass psiClass) {
    String qualifiedName = psiClass.getQualifiedName();
    if (qualifiedName == null) return null;
    return new McpImportCandidate(qualifiedName, sourceOf(psiClass), psiClass.isDeprecated(), false);
  }

  private static @Nullable McpImportCandidate toStaticCandidate(@NotNull PsiMember member) {
    String memberName = member.getName();
    PsiClass containingClass = member.getContainingClass();
    String className = containingClass == null ? null : containingClass.getQualifiedName();
    if (memberName == null || className == null) return null;
    boolean deprecated = member instanceof PsiMethod method ? method.isDeprecated()
                                                            : member instanceof PsiField field && field.isDeprecated();
    return new McpImportCandidate(className + "." + memberName, sourceOf(member), deprecated, true);
  }

  /** Returns the module or the library that holds {@code member}, or null when neither is known. */
  private static @Nullable String sourceOf(@NotNull PsiMember member) {
    Module module = ModuleUtilCore.findModuleForPsiElement(member);
    if (module != null) return module.getName();

    VirtualFile virtualFile = PsiUtilCore.getVirtualFile(member);
    if (virtualFile == null) return null;
    List<OrderEntry> orderEntries = ProjectFileIndex.getInstance(member.getProject()).getOrderEntriesForFile(virtualFile);
    return orderEntries.isEmpty() ? null : orderEntries.getFirst().getPresentableName();
  }
}
