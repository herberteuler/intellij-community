"""Macros for IntelliJ-based IDE development builds."""

load("@dev_launch_paths//:paths.bzl", "HOME", "OUTPUT_BASE", "WORKSPACE_ROOT")
load("@intellij_add_opens//:intellij_add_opens.bzl", "INTELLIJ_ADD_OPENS")
load("@rules_java//java:defs.bzl", "java_binary")
load("//platform/build-scripts/bazel-rules:intellij_dev_dist.bzl", "IntellijDevDistInfo")

# Names the prepared distribution for whoever consumes one - `PreBuiltDevMain` when it is a launcher, the IDE Starter's
# prebuilt dev-build runner when it is a test. Keep in sync with `DevIdeConfig.CONFIG_PATH_PROPERTY`, which is where the
# reading side of this contract lives.
DEV_IDE_CONFIG_PATH_PROPERTY = "idea.ide.config.path"

def intellij_dev_dist_config(name, dist, visibility = None, tags = []):
    """A single-file label for an assembled dev distribution's config file, for `$(rlocationpath ...)`.

    That expansion takes a label naming exactly one file, which a dist target - two outputs, one of them declared rather
    than predeclared - is not. Its `ide_config` output group is how it gets one.

    A consumer declares both this and the dist itself in `data`, and they must stay siblings in the runfiles tree: the
    config names the home relatively, so that the pair survives being read from a different path than it was written to.

    `tags` is the filegroup's; a consumer that must stay out of a wildcard build passes `["manual"]`.
    """
    native.filegroup(
        name = name,
        srcs = [dist],
        output_group = "ide_config",
        tags = tags,
        visibility = visibility,
    )

DEFAULT_JVM_FLAGS = [
    "--enable-native-access=ALL-UNNAMED",
    "-ea",
    "-Didea.jre.check=true",
    "-Didea.is.internal=true",
    "-Didea.debug.mode=true",
    "-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader",
    "-Djava.nio.file.spi.DefaultFileSystemProvider=com.intellij.platform.core.nio.fs.MultiRoutingFileSystemProvider",
]

# The data directories of the IDE. `_runtime_jvm_flags` owns them, and a flag that states one would lose to its defaults.
_LAUNCHER_DATA_PROPERTIES = ["idea.config.path", "idea.system.path", "idea.log.path"]

def _runtime_jvm_flags(name, jvm_flags, platform_prefix, config_path, system_path, data_name = None, manifest_class_path = True):
    """The flags an IDE needs to run, independent of how it was assembled.

    `$${...}` is a literal `${...}` the java stub expands at launch; `BUILD_WORKSPACE_DIRECTORY` is set by `bazel run`,
    so a launcher started any other way must export it itself.

    The config and system directories are `out/dev-data/<name>/config` and `out/dev-data/<name>/system` unless
    `config_path` and `system_path` say otherwise. On macOS the launcher makes `out/dev-data` a link to a directory
    outside the workspace. `jvm_flags` must not state a data directory.

    `manifest_class_path` adds the Windows flag of the java stub. A launcher that writes a plain `-cp` passes False.
    """
    for flag in jvm_flags:
        for key in _LAUNCHER_DATA_PROPERTIES:
            if flag.startswith("-D%s=" % key):
                fail("%s: `%s` states a data directory, which the launcher owns; use `config_path` or `system_path`" % (name, flag))

    # Use provided paths or defaults based on target name
    data_name = data_name or name
    effective_config_path = config_path if config_path else "$${BUILD_WORKSPACE_DIRECTORY}/out/dev-data/" + data_name + "/config"
    effective_system_path = system_path if system_path else "$${BUILD_WORKSPACE_DIRECTORY}/out/dev-data/" + data_name + "/system"

    all_jvm_flags = DEFAULT_JVM_FLAGS + [
        "-Didea.plugins.path=" + effective_config_path + "/plugins",
        "-Didea.log.path=" + effective_system_path + "/log",
    ] + jvm_flags

    if platform_prefix:
        all_jvm_flags = all_jvm_flags + ["-Didea.platform.prefix=" + platform_prefix]

    all_jvm_flags = all_jvm_flags + [
        "-Didea.config.path=" + effective_config_path,
        "-Didea.system.path=" + effective_system_path,
    ]
    if not manifest_class_path:
        return all_jvm_flags

    # On Windows the java stub folds a long classpath into one jar with a `Class-Path` manifest attribute.
    # `PathClassLoader` is the system class loader and expands that jar only with this flag.
    # https://github.com/bazelbuild/bazel/blob/93cde47ab3236b3b7124b41824f843f3659064de/src/tools/launcher/java_launcher.cc#L385
    return all_jvm_flags + select({
        "@bazel_tools//src/conditions:windows": ["-Didea.reset.classpath.from.manifest=true"],
        "//conditions:default": [],
    })

_PREBUILT_DEV_MAIN_CLASS = "com.intellij.platform.bootstrap.dev.PreBuiltDevMain"

# `PreBuiltDevMain`. The module carries no build scripts.
_LAUNCHER_MODULE = "@community//platform/bootstrap/dev"

def intellij_dev_prebuilt_binary(
        name,
        dist,
        platform_prefix = None,
        jvm_flags = [],
        env = {},
        config_path = None,
        system_path = None,
        program_args = [],
        visibility = None,
        local_home_tool = None,
        data = []):
    """Launches a built distribution or a linked local home without packaging it.

    The distribution declares its product and additional modules.
    When it supplies local metadata, local_home_tool prepares a temporary home from its component runfiles.
    `data` is the launcher's extra runfiles, on top of the distribution and its config.
    """

    # Manual, like the distribution in `data`: a wildcard build must not compose it. `bazel run` names the launcher and
    # is not affected.
    tags = ["manual"]

    ide_config = name + "_ide_config"
    dist_target = name + "_distribution"
    native.alias(name = dist_target, actual = dist, tags = tags, visibility = ["//visibility:private"])
    intellij_dev_dist_config(name = ide_config, dist = dist_target, tags = tags, visibility = ["//visibility:private"])

    local_home_data = [local_home_tool] if local_home_tool else []
    local_home_flags = ["-Didea.dev.local.home.tool=$(rlocationpath %s)" % local_home_tool] if local_home_tool else []

    java_binary(
        name = name,
        visibility = visibility,
        runtime_deps = [_LAUNCHER_MODULE],
        main_class = _PREBUILT_DEV_MAIN_CLASS,
        tags = tags,
        data = data + [dist_target, ide_config] + local_home_data,
        jvm_flags = _runtime_jvm_flags(name, jvm_flags, platform_prefix, config_path, system_path) + local_home_flags + [
            "-D%s=$(rlocationpath %s)" % (DEV_IDE_CONFIG_PATH_PROPERTY, ide_config),
            # Not a build-time input: `AppMode.getDevIdeaProjectDir` and the webview native bridge read it at runtime,
            # and a dev launch has it only because `DevMainImpl` sets it from the project root it just built against.
            "-Didea.dev.project.root=$${BUILD_WORKSPACE_DIRECTORY}",
        ],
        env = env,
        add_opens = INTELLIJ_ADD_OPENS,
        args = program_args,
    )

def _launcher_runfile_path(ctx, file):
    path = file.short_path
    return path[3:] if path.startswith("../") else ctx.workspace_name + "/" + path

def _java_runfile_path(ctx, java_runtime):
    path = java_runtime.java_executable_runfiles_path
    if path.startswith("/"):
        return path
    return path[3:] if path.startswith("../") else ctx.workspace_name + "/" + path

def _intellij_dev_launcher_impl(ctx):
    java_runtime = ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
    windows = ctx.target_platform_has_constraint(ctx.attr._windows[platform_common.ConstraintValueInfo])
    executable = ctx.actions.declare_file(ctx.label.name + (".exe" if windows else ""))
    ctx.actions.symlink(output = executable, target_file = ctx.executable._launcher, is_executable = True)

    # `$$` is a literal `$`, and the launcher expands `${NAME}` at launch, as the java stub's shell did.
    targets = ctx.attr.data + [ctx.attr.dist, ctx.attr.ide_config]
    jvm_flags = ctx.fragments.java.default_jvm_opts + [
        ctx.expand_make_variables("jvm_flags", ctx.expand_location(flag, targets), {})
        for flag in ctx.attr.jvm_flags
    ] + ["--add-opens=%s=ALL-UNNAMED" % package for package in ctx.attr.add_opens]
    manifest = ctx.actions.declare_file(ctx.label.name + ".launch.json")
    ctx.actions.write(manifest, json.encode({
        "version": 1,
        "java": _java_runfile_path(ctx, java_runtime),
        "ideConfig": _launcher_runfile_path(ctx, ctx.file.ide_config),
        "beforeRun": _launcher_runfile_path(ctx, ctx.executable.before_run) if ctx.attr.before_run else "",
        "jvmFlags": jvm_flags,
        "home": ctx.attr.home,
    }))

    runfiles = ctx.runfiles(files = [executable, manifest, ctx.file.ide_config], transitive_files = java_runtime.files)
    for target in [ctx.attr.dist, ctx.attr._launcher] + ([ctx.attr.before_run] if ctx.attr.before_run else []) + ctx.attr.data:
        runfiles = runfiles.merge(ctx.runfiles(transitive_files = target[DefaultInfo].files)).merge(target[DefaultInfo].default_runfiles)
    return [
        DefaultInfo(executable = executable, files = depset([executable, manifest]), runfiles = runfiles),
        RunEnvironmentInfo(environment = ctx.attr.env),
    ]

intellij_dev_launcher = rule(
    doc = """Starts a composed dev distribution through the launcher, `bazel run //<package>:<name>`.

The launcher reads `<name>.launch.json`, which this rule writes, links the distribution's local home under
`$BUILD_WORKSPACE_DIRECTORY/<home>` in its own process, and replaces itself with the IDE's JVM in the workspace. On
macOS it first makes `out/dev-data` a link to the dev-data root of the checkout. It takes the java stub's wrapper
options, so the IDE's Bazel plugin can debug it. See `community/build/dev-dist-tools/bins/dev-launcher`.""",
    implementation = _intellij_dev_launcher_impl,
    executable = True,
    fragments = ["java"],
    toolchains = ["@bazel_tools//tools/jdk:runtime_toolchain_type"],
    attrs = {
        "dist": attr.label(mandatory = True, doc = "The composed distribution, whose runfiles hold its home or its components."),
        "ide_config": attr.label(mandatory = True, allow_single_file = True, doc = "The `intellij_dev_dist_config` of `dist`."),
        "jvm_flags": attr.string_list(doc = "JVM flags; `$(location)` and make variables expand, and `${NAME}` expands at launch."),
        "add_opens": attr.string_list(doc = "Packages opened to the unnamed module, as `java_binary.add_opens`."),
        "env": attr.string_dict(doc = "Environment variables `bazel run` sets for the launcher."),
        "data": attr.label_list(allow_files = True, doc = "Extra runfiles of the launcher."),
        "before_run": attr.label(executable = True, cfg = "target", doc = "An executable the launcher runs in the workspace before the IDE, and fails with."),
        "home": attr.string(mandatory = True, doc = "The workspace-relative directory under which each launch links its home, under `out/dev-data`."),
        "_launcher": attr.label(default = Label("//build/dev-dist-tools/bins/dev-launcher:dev-launcher_opt"), executable = True, cfg = "target"),
        "_windows": attr.label(default = Label("@platforms//os:windows")),
    },
)

def intellij_dev_launcher_binary(
        name,
        dist,
        ide_config,
        jvm_flags = [],
        env = {},
        program_args = [],
        data = [],
        before_run_main_class = "",
        before_run_runtime_deps = [],
        data_name = None,
        visibility = None):
    """The launcher of a composed dev distribution, with the flags `intellij_dev_prebuilt_binary` gives its java stub.

    `dist` and `ide_config` are the distribution and its `intellij_dev_dist_config`. The home is linked under
    `out/dev-data/<name>/homes`, one directory per launch, beside the launcher's config and system directories. On macOS
    `out/dev-data` is a link to a directory outside the workspace. With `before_run_main_class`, a `java_binary`
    `<name>_before_run` runs that class over `before_run_runtime_deps` first. `data_name` replaces `<name>` in the
    dev data directories, so two launchers of one row can share them.
    """
    data_name = data_name or name
    tags = ["manual"]
    before_run = None
    if before_run_main_class:
        before_run = name + "_before_run"
        java_binary(
            name = before_run,
            main_class = before_run_main_class,
            runtime_deps = before_run_runtime_deps,
            tags = tags,
            visibility = ["//visibility:private"],
        )
    intellij_dev_launcher(
        name = name,
        visibility = visibility,
        tags = tags,
        dist = dist,
        ide_config = ide_config,
        before_run = before_run,
        # The IDE starts in the workspace, so a relative path in a flag resolves as it does for the run configuration.
        jvm_flags = _runtime_jvm_flags(name, jvm_flags, platform_prefix = None, config_path = None, system_path = None, data_name = data_name) + [
            # Not a build-time input: `AppMode.getDevIdeaProjectDir` and the webview native bridge read it at runtime,
            # and a dev launch has it only because `DevMainImpl` sets it from the project root it just built against.
            "-Didea.dev.project.root=$${BUILD_WORKSPACE_DIRECTORY}",
        ],
        add_opens = INTELLIJ_ADD_OPENS,
        env = env,
        args = program_args,
        data = data,
        home = "out/dev-data/%s/homes" % data_name,
    )

# The directory of the runfiles tree that holds the home of a `java` launcher.
_JAVA_LAUNCH_HOME = "ide_home"

# The variables that a flag of a `java` launcher can name. `java` expands none, so the rule resolves them at analysis.
_JAVA_LAUNCH_VARIABLES = {
    "BUILD_WORKSPACE_DIRECTORY": WORKSPACE_ROOT,
    "HOME": HOME,
}

def _resolve_variables(label, flag):
    """`flag` with each `${NAME}` of `_JAVA_LAUNCH_VARIABLES` replaced. Any other `${NAME}` fails."""
    result = flag
    for name, value in _JAVA_LAUNCH_VARIABLES.items():
        result = result.replace("${%s}" % name, value)
    if "${" in result:
        fail("%s: `%s` names a variable that a java launcher cannot expand; it knows only %s" % (label, flag, sorted(_JAVA_LAUNCH_VARIABLES.keys())))
    return result

def _execroot_path(ctx, path):
    """The absolute path of the exec path `path`."""
    return "%s/execroot/%s/%s" % (OUTPUT_BASE, ctx.workspace_name, path)

def _windows_path(label, path):
    """`path` with backslashes, for a line of a `.cmd` file. `cmd.exe` expands `%` and ends a quoted path at `"`, so both fail."""
    if "%" in path or '"' in path:
        fail("%s: the path %s has a `%%` or a `\"`, which a `.cmd` file cannot hold" % (label, path))
    return path.replace("/", "\\")

def _java_file(ctx, java_runtime):
    for file in java_runtime.files.to_list():
        if file.path == java_runtime.java_executable_exec_path:
            return file
    fail("%s: the Java runtime has no file %s" % (ctx.label, java_runtime.java_executable_exec_path))

def _runfiles_home(ctx, placement, dist):
    """The entries of the runfiles home: a dictionary from a destination to its `File`."""

    # Analysis cannot see the mode of a source file, so an executable source file gets a copy, which Bazel leaves
    # executable.
    home = {}
    executables = {destination: True for destination in placement.executables}
    for destination, file in placement.files.items():
        if destination in executables and file.is_source:
            copy = ctx.actions.declare_file("%s.executables/%s" % (ctx.label.name, destination))
            ctx.actions.run_shell(
                outputs = [copy],
                inputs = [file],
                command = 'cp "$1" "$2"',
                arguments = [file.path, copy.path],
                mnemonic = "DevLaunchExecutableCopy",
                progress_message = "Copying the executable %s of %%{label}" % destination,
            )
            file = copy
        home[destination] = file
    home.update(placement.trees)
    home["fingerprint.txt"] = dist.fingerprint
    if dist.plugin_classpath:
        home["plugins/plugin-classpath.txt"] = dist.plugin_classpath
    return home

def _intellij_dev_java_launcher_impl(ctx):
    windows = ctx.target_platform_has_constraint(ctx.attr._windows[platform_common.ConstraintValueInfo])
    dist = ctx.attr.dist[IntellijDevDistInfo]
    placement = dist.placement
    if placement == None or dist.core_classpath == None:
        fail("%s: %s is no local launch with a placement for every component" % (ctx.label, ctx.attr.dist.label))
    vm_options = [destination for destination in placement.files if destination.startswith("bin/") and destination.endswith(".vmoptions")]
    if len(vm_options) != 1:
        fail("%s: the home has no single bin/*.vmoptions file: %s" % (ctx.label, vm_options))
    for destination in ["bin/idea.properties", "bin/product-info.json"]:
        if destination not in placement.files:
            fail("%s: the home has no %s" % (ctx.label, destination))

    java_runtime = ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
    executable = ctx.actions.declare_file(ctx.label.name + (".cmd" if windows else ""))
    runfiles_directory = _execroot_path(ctx, executable.path) + ".runfiles"

    # The home and the files that the argument file action reads. The export holds the same files at the same
    # destinations as the placement of the local launch.
    if windows:
        if not ctx.attr.export:
            fail("%s: a java launcher on Windows needs `export`, the self-contained distribution of the row" % ctx.label)
        export = ctx.attr.export[IntellijDevDistInfo]
        tree = export.home.path
        home_path = _windows_path(ctx.label, _execroot_path(ctx, tree))
        sources = struct(
            ide_config = export.ide_config,
            idea_properties = tree + "/bin/idea.properties",
            vm_options = tree + "/" + vm_options[0],
            product_info = tree + "/bin/product-info.json",
            core_classpath = tree + "/core-classpath.txt",
            inputs = [export.ide_config, export.home],
        )
    else:
        export = None
        home_path = runfiles_directory + "/" + _JAVA_LAUNCH_HOME
        sources = struct(
            ide_config = dist.ide_config,
            idea_properties = placement.files["bin/idea.properties"],
            vm_options = placement.files[vm_options[0]],
            product_info = placement.files["bin/product-info.json"],
            core_classpath = dist.core_classpath,
            inputs = [
                dist.ide_config,
                placement.files["bin/idea.properties"],
                placement.files[vm_options[0]],
                placement.files["bin/product-info.json"],
                dist.core_classpath,
            ],
        )

    # `$$` is a literal `$` in the macro, so a flag reaches the rule as `${NAME}`.
    targets = ctx.attr.data + [ctx.attr.dist]
    flags = ctx.fragments.java.default_jvm_opts + [
        ctx.expand_make_variables("jvm_flags", ctx.expand_location(flag, targets), {})
        for flag in ctx.attr.jvm_flags
    ] + ["--add-opens=%s=ALL-UNNAMED" % package for package in ctx.attr.add_opens]
    flags_file = ctx.actions.declare_file(ctx.label.name + ".jvm-flags.txt")
    ctx.actions.write(flags_file, "".join([_resolve_variables(ctx.label, flag) + "\n" for flag in flags]))

    # Each value is one program argument, as the run configuration of the IDE passes it. So the rule splits no value at
    # a space, which Bazel does for a value of `args`.
    program_args = [
        _resolve_variables(ctx.label, ctx.expand_make_variables("program_args", ctx.expand_location(argument, targets), {}))
        for argument in ctx.attr.program_args
    ]

    argfile = ctx.actions.declare_file(ctx.label.name + ".jvm.args")
    arguments = ctx.actions.args()
    arguments.add("jvm-args")
    arguments.add(sources.ide_config, format = "--ide-config=%s")
    arguments.add(home_path, format = "--home=%s")
    arguments.add(sources.idea_properties, format = "--idea-properties=%s")
    arguments.add(sources.vm_options, format = "--vm-options=%s")
    arguments.add(vm_options[0], format = "--vm-options-destination=%s")
    arguments.add(sources.product_info, format = "--product-info=%s")
    arguments.add(sources.core_classpath, format = "--core-classpath=%s")
    arguments.add(flags_file, format = "--flags-file=%s")
    arguments.add_all(program_args, format_each = "--program-arg=%s")
    if "modules/module-descriptors.dat" in placement.files:
        arguments.add("--runtime-module-repository")
    arguments.add(argfile, format = "--output=%s")
    ctx.actions.run(
        executable = ctx.executable._jvm_args_tool,
        arguments = [arguments],
        inputs = sources.inputs + [flags_file],
        outputs = [argfile],
        # The file holds absolute local paths, so no shared cache may keep it.
        execution_requirements = {"no-remote-cache": "1", "no-remote-exec": "1"},
        mnemonic = "DevLaunchJvmArgs",
        progress_message = "Writing the JVM arguments of %{label}",
    )

    java = _execroot_path(ctx, java_runtime.java_executable_exec_path)
    if windows:
        # A link to `java.exe` cannot find the DLLs of the runtime, so the stub runs the real file. The macro passes no
        # `args` on Windows, because the stub names the argument file itself.
        ctx.actions.write(
            executable,
            '@echo off\r\n"%s" "@%s" %%*\r\n' % (_windows_path(ctx.label, java), _windows_path(ctx.label, _execroot_path(ctx, argfile.path))),
            is_executable = True,
        )
        runfiles = ctx.runfiles(files = [executable, argfile, export.home, export.ide_config], transitive_files = java_runtime.files)
        environment = dict(ctx.attr.env)
        launch_argfile = _execroot_path(ctx, argfile.path)
        working_directory = WORKSPACE_ROOT
    else:
        ctx.actions.symlink(output = executable, target_file = _java_file(ctx, java_runtime), is_executable = True)
        runfiles = ctx.runfiles(
            files = [executable],
            transitive_files = java_runtime.files,
            symlinks = {ctx.label.name + ".jvm.args": argfile},
            root_symlinks = {_JAVA_LAUNCH_HOME + "/" + destination: file for destination, file in _runfiles_home(ctx, placement, dist).items()},
        )

        # The launcher of ADR 0014 gives these two to the IDE, as the java stub does.
        environment = dict(ctx.attr.env)
        environment.update({"JAVA_RUNFILES": runfiles_directory, "RUNFILES_DIR": runfiles_directory})
        launch_argfile = "%s/%s/%s.jvm.args" % (runfiles_directory, ctx.workspace_name, ctx.label.name)
        working_directory = "%s/%s" % (runfiles_directory, ctx.workspace_name)
    for target in ctx.attr.data:
        runfiles = runfiles.merge(ctx.runfiles(transitive_files = target[DefaultInfo].files)).merge(target[DefaultInfo].default_runfiles)

    # The run handler of the devkit Bazel plugin reads this file. It starts `java` with the debug options before the
    # argument file.
    launch = ctx.actions.declare_file(ctx.label.name + ".launch.json")
    ctx.actions.write(launch, json.encode_indent({
        "version": 2,
        "java": java,
        "argfile": launch_argfile,
        "runfilesDirectory": runfiles_directory,
        "workingDirectory": working_directory,
        "env": environment,
    }) + "\n")
    return [
        DefaultInfo(executable = executable, files = depset([executable, argfile, launch]), runfiles = runfiles),
        RunEnvironmentInfo(environment = environment),
    ]

intellij_dev_java_launcher = rule(
    doc = """Starts a composed dev distribution with `java` itself, `bazel run //<package>:<name>`.

The executable is a link to the `java` of the Java runtime. Its `args` are `@<name>.jvm.args`, the argument file that
`dev-launcher jvm-args` writes at build time. The file ends with the main class and the program arguments of the row.
So `bazel run //<package>:<name> -- <argument>` adds a program argument after them. The home is
`<name>.runfiles/ide_home`, which Bazel links from the placement of the components. So no process runs before the JVM,
and a launch writes no file. `bazel run` starts the executable in `<name>.runfiles/_main`, so every path of the
argument file is absolute. A caller adds JVM flags through `JDK_JAVA_OPTIONS`, and `java` reads them before the
argument file. `<name>.launch.json` states the absolute paths of `java`, the argument file and the runfiles tree, and
the environment. The devkit Bazel plugin reads it to debug the row.

Windows builds no runfiles tree, and a link to `java.exe` does not start. So on Windows the executable is `<name>.cmd`.
It runs the `java.exe` of the Java runtime by its absolute path with `@<name>.jvm.args` and its own arguments. The home
is the directory of `export`. `<name>.launch.json` names the real `java.exe` and the workspace as the working
directory.""",
    implementation = _intellij_dev_java_launcher_impl,
    executable = True,
    fragments = ["java"],
    toolchains = ["@bazel_tools//tools/jdk:runtime_toolchain_type"],
    attrs = {
        "dist": attr.label(mandatory = True, providers = [IntellijDevDistInfo], doc = "The local launch of the distribution, the `_dist_launch` target. Its placement names the files of the home."),
        "export": attr.label(providers = [IntellijDevDistInfo], doc = "The self-contained distribution, the `_dist` target. The home on Windows, where it is mandatory."),
        "jvm_flags": attr.string_list(doc = "JVM flags; `$(location)` and make variables expand, and `${BUILD_WORKSPACE_DIRECTORY}` and `${HOME}` resolve at analysis."),
        "add_opens": attr.string_list(doc = "Packages opened to the unnamed module, as `java_binary.add_opens`."),
        "env": attr.string_dict(doc = "Environment variables `bazel run` sets for the IDE."),
        "data": attr.label_list(allow_files = True, doc = "Extra runfiles of the launcher."),
        "program_args": attr.string_list(doc = "The program arguments of the row, which the argument file holds after the main class. Each value is one argument; `$(location)` and make variables expand, and `${BUILD_WORKSPACE_DIRECTORY}` and `${HOME}` resolve at analysis. The first one names a custom command."),
        "_jvm_args_tool": attr.label(default = Label("//build/dev-dist-tools/bins/dev-launcher:dev-launcher_opt"), executable = True, cfg = "exec"),
        "_windows": attr.label(default = Label("@platforms//os:windows")),
    },
)

def intellij_dev_java_launcher_binary(
        name,
        dist,
        export,
        jvm_flags = [],
        env = {},
        program_args = [],
        data = [],
        visibility = None):
    """The `java` launcher of a composed dev distribution, with the flags of `intellij_dev_launcher_binary`.

    `dist` is the `_dist_launch` target of the distribution, and `export` is its `_dist` target. Only Windows builds
    `export`. The config and system directories are the ones of `intellij_dev_launcher_binary`, as absolute paths.
    """
    intellij_dev_java_launcher(
        name = name,
        visibility = visibility,
        tags = ["manual"],
        dist = dist,
        export = select({
            "@platforms//os:windows": export,
            "//conditions:default": None,
        }),
        # The argument file has a plain `-cp`, so `PathClassLoader` needs no manifest flag.
        jvm_flags = _runtime_jvm_flags(name, jvm_flags, platform_prefix = None, config_path = None, system_path = None, manifest_class_path = False) + [
            # Not a build-time input: `AppMode.getDevIdeaProjectDir` and the webview native bridge read it at runtime,
            # and a dev launch has it only because `DevMainImpl` sets it from the project root it just built against.
            "-Didea.dev.project.root=$${BUILD_WORKSPACE_DIRECTORY}",
        ],
        add_opens = INTELLIJ_ADD_OPENS,
        env = env,
        # `bazel run` starts the executable in `<name>.runfiles/_main`, which holds the argument file. The file holds
        # the program arguments, so a `bazel run` argument comes after them. The Windows stub names the file itself.
        args = select({
            "@platforms//os:windows": [],
            "//conditions:default": ["@%s.jvm.args" % name],
        }),
        program_args = program_args,
        data = data,
    )
