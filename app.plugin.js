const {
  withXcodeProject,
  withSettingsGradle,
  withProjectBuildGradle,
  withAppBuildGradle,
  withGradleProperties,
  withDangerousMod,
} = require('@expo/config-plugins');
const path = require('path');
const fs = require('fs');

/**
 * Parse a Java .properties file into an array of { type, key, value } entries.
 */
function parsePropertiesFile(filePath) {
  if (!fs.existsSync(filePath)) return [];
  const lines = fs.readFileSync(filePath, 'utf8').split('\n');
  const entries = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) continue;
    const eqIndex = trimmed.indexOf('=');
    if (eqIndex === -1) continue;
    entries.push({
      type: 'property',
      key: trimmed.slice(0, eqIndex).trim(),
      value: trimmed.slice(eqIndex + 1).trim(),
    });
  }
  return entries;
}

/**
 * Expo Config Plugin for @dolami-inc/react-native-expo-unity.
 *
 * iOS:
 * - Injects required Xcode build settings (bitcode, C++17)
 * - Adds a build phase that embeds UnityFramework.framework into the app
 *   bundle at build time (device builds only)
 *
 * Android:
 * - Includes the :unityLibrary Gradle module and .androidlib subprojects in settings.gradle
 * - Copies Unity gradle properties (unity.*, unityStreamingAssets) to host gradle.properties
 * - Adds flatDir repos for Unity's .aar/.jar libs in root build.gradle
 * - Strips LAUNCHER intent from Unity's AndroidManifest (prevents duplicate app icon)
 * - Adds the unityLibrary dependency and NDK abiFilters in app/build.gradle
 *
 * @param {object} config - Expo config
 * @param {object} options
 * @param {string} [options.unityPath] — absolute path to the Unity iOS build
 *   artifacts directory. Defaults to `<projectRoot>/unity/builds/ios`.
 *   Can also be set via the EXPO_UNITY_PATH environment variable.
 * @param {string} [options.androidUnityPath] — absolute path to the Unity
 *   Android export directory. Defaults to `<projectRoot>/unity/builds/android`.
 *   Can also be set via the EXPO_UNITY_ANDROID_PATH environment variable.
 */
const withExpoUnity = (config, options = {}) => {
  // -- iOS --
  config = withXcodeProject(config, (config) => {
    const xcodeProject = config.modResults;
    const projectRoot = config.modRequest.projectRoot;

    const unityPath =
      options.unityPath ||
      process.env.EXPO_UNITY_PATH ||
      path.join(projectRoot, 'unity', 'builds', 'ios');

    // Build settings
    const configurations = xcodeProject.pbxXCBuildConfigurationSection();
    for (const key of Object.keys(configurations)) {
      const configuration = configurations[key];
      if (typeof configuration !== 'object' || !configuration.buildSettings) {
        continue;
      }
      const settings = configuration.buildSettings;
      settings['ENABLE_BITCODE'] = 'NO';
      settings['CLANG_CXX_LANGUAGE_STANDARD'] = '"c++17"';
    }

    // Embed UnityFramework via build script phase
    const frameworkSrc = path.join(unityPath, 'UnityFramework.framework');
    if (fs.existsSync(frameworkSrc)) {
      const target = xcodeProject.getFirstTarget();

      const script = [
        'if [ "${PLATFORM_NAME}" = "iphoneos" ]; then',
        '  FRAMEWORK_SRC="' + frameworkSrc + '"',
        '  DEST="${BUILT_PRODUCTS_DIR}/${FRAMEWORKS_FOLDER_PATH}"',
        '  mkdir -p "${DEST}"',
        '  rsync -av --delete "${FRAMEWORK_SRC}" "${DEST}/"',
        '  if [ -n "${EXPANDED_CODE_SIGN_IDENTITY}" ]; then',
        '    codesign --force --sign "${EXPANDED_CODE_SIGN_IDENTITY}" "${DEST}/UnityFramework.framework"',
        '  fi',
        'fi',
      ].join('\n');

      xcodeProject.addBuildPhase(
        [],
        'PBXShellScriptBuildPhase',
        'Embed UnityFramework',
        target.uuid,
        {
          shellPath: '/bin/sh',
          shellScript: script,
        }
      );
    }

    return config;
  });

  // -- Android: settings.gradle (include :unityLibrary module + subprojects) --
  config = withSettingsGradle(config, (config) => {
    const projectRoot = config.modRequest.projectRoot;
    const androidUnityPath =
      options.androidUnityPath ||
      process.env.EXPO_UNITY_ANDROID_PATH ||
      path.join(projectRoot, 'unity', 'builds', 'android');

    const unityLibDir = path.join(androidUnityPath, 'unityLibrary');

    // Include :unityLibrary module
    const includeSnippet = `include ':unityLibrary'`;
    const projectSnippet = `project(':unityLibrary').projectDir = new File('${unityLibDir}')`;

    if (!config.modResults.contents.includes(includeSnippet)) {
      config.modResults.contents +=
        `\n// Unity as a Library\n${includeSnippet}\n${projectSnippet}\n`;
    }

    // Auto-discover .androidlib subprojects (e.g. xrmanifest.androidlib)
    if (fs.existsSync(unityLibDir)) {
      const subprojects = fs.readdirSync(unityLibDir).filter((name) =>
        name.endsWith('.androidlib') &&
        fs.existsSync(path.join(unityLibDir, name, 'build.gradle'))
      );

      for (const sub of subprojects) {
        const subInclude = `include ':unityLibrary:${sub}'`;
        if (!config.modResults.contents.includes(subInclude)) {
          config.modResults.contents += `${subInclude}\n`;
        }
      }
    }

    return config;
  });

  // -- Android: Strip LAUNCHER intent from Unity's AndroidManifest --
  // Unity exports include a LAUNCHER intent filter on UnityPlayerGameActivity,
  // which creates a duplicate app icon. Since Unity is embedded (not standalone),
  // we remove it so only the host app's launcher entry exists.
  config = withDangerousMod(config, [
    'android',
    (config) => {
      const projectRoot = config.modRequest.projectRoot;
      const androidUnityPath =
        options.androidUnityPath ||
        process.env.EXPO_UNITY_ANDROID_PATH ||
        path.join(projectRoot, 'unity', 'builds', 'android');

      const manifestPath = path.join(
        androidUnityPath,
        'unityLibrary',
        'src',
        'main',
        'AndroidManifest.xml'
      );

      if (fs.existsSync(manifestPath)) {
        let manifest = fs.readFileSync(manifestPath, 'utf8');

        // Remove the entire <intent-filter> block containing LAUNCHER
        const launcherRegex =
          /\s*<intent-filter>\s*<category[^>]*LAUNCHER[^>]*\/>\s*<action[^>]*MAIN[^>]*\/>\s*<\/intent-filter>/g;
        const altLauncherRegex =
          /\s*<intent-filter>\s*<action[^>]*MAIN[^>]*\/>\s*<category[^>]*LAUNCHER[^>]*\/>\s*<\/intent-filter>/g;

        const patched = manifest
          .replace(launcherRegex, '')
          .replace(altLauncherRegex, '');

        if (patched !== manifest) {
          fs.writeFileSync(manifestPath, patched, 'utf8');
        }
      }

      return config;
    },
  ]);

  // -- Android: root build.gradle — inject newArch extra properties onto Unity
  //    subprojects BEFORE ReactRootProjectPlugin runs --
  //
  // React Native 0.83+'s ReactRootProjectPlugin (applied to android/build.gradle)
  // iterates every subproject and does:
  //   if (subproject.hasProperty("react.newArchEnabled")) {
  //     subproject.setProperty("react.newArchEnabled", "true")
  //   }
  // `hasProperty` returns true via inheritance (from root gradle.properties),
  // but `setProperty` on a bare Unity subproject like `xrmanifest.androidlib`
  // throws because the property doesn't exist as a direct project property:
  //   "Could not set unknown property 'react.newArchEnabled' for project
  //    ':unityLibrary:xrmanifest.androidlib'"
  //
  // Patching each subproject's build.gradle is too late — the RN plugin runs
  // during the root project's `apply` phase, before any subproject's build.gradle
  // is evaluated. Instead, we inject a `subprojects { }` block in the ROOT
  // android/build.gradle BEFORE the RN plugin apply line. Gradle's subprojects
  // closure runs the action immediately against all currently-known subprojects,
  // so by the time ReactRootProjectPlugin iterates, Unity subprojects already
  // have the extra properties and both hasProperty + setProperty succeed.
  config = withProjectBuildGradle(config, (config) => {
    const MARKER = '// react-native-expo-unity: RN newArch shim';
    if (config.modResults.contents.includes(MARKER)) {
      return config;
    }

    const shimBlock = [
      '',
      MARKER,
      "subprojects { sub ->",
      "  if (sub.path.startsWith(':unityLibrary')) {",
      "    sub.ext.set('react.newArchEnabled', 'true')",
      "    sub.ext.set('newArchEnabled', 'true')",
      '  }',
      '}',
      '',
    ].join('\n');

    // Insert before `apply plugin: "com.facebook.react.rootproject"` so the
    // ext props are set on Unity subprojects before RN plugin reads them.
    const applyRegex = /apply plugin:\s*["']com\.facebook\.react\.rootproject["']/;
    if (applyRegex.test(config.modResults.contents)) {
      config.modResults.contents = config.modResults.contents.replace(
        applyRegex,
        `${shimBlock}$&`
      );
    } else {
      // Fallback: append at end
      config.modResults.contents += `\n${shimBlock}`;
    }

    return config;
  });

  // -- Android: gradle.properties (copy Unity properties to host project) --
  // Unity's build.gradle references properties like `unityStreamingAssets` and
  // `unity.*` that are defined in the Unity export's gradle.properties. When the
  // unityLibrary is included as a subproject, it inherits the host's properties,
  // so we need to copy the required ones over.
  config = withGradleProperties(config, (config) => {
    const projectRoot = config.modRequest.projectRoot;
    const androidUnityPath =
      options.androidUnityPath ||
      process.env.EXPO_UNITY_ANDROID_PATH ||
      path.join(projectRoot, 'unity', 'builds', 'android');

    const unityPropsPath = path.join(androidUnityPath, 'gradle.properties');
    const unityProps = parsePropertiesFile(unityPropsPath);

    // Only copy unity-specific properties (unity.* and unityStreamingAssets)
    const existingKeys = new Set(
      config.modResults
        .filter((p) => p.type === 'property')
        .map((p) => p.key)
    );

    for (const prop of unityProps) {
      if (
        (prop.key.startsWith('unity.') || prop.key === 'unityStreamingAssets' || prop.key === 'unityTemplateVersion') &&
        !existingKeys.has(prop.key)
      ) {
        config.modResults.push(prop);
      }
    }

    return config;
  });

  // -- Android: build.gradle (flatDir repos for Unity's native libs) --
  // This must go in the root build.gradle (not settings.gradle) because
  // `allprojects` is not a valid method in settings.gradle on Gradle 8+.
  config = withProjectBuildGradle(config, (config) => {
    const flatDirSnippet = `flatDir { dirs "\${project(':unityLibrary').projectDir}/libs" }`;
    if (!config.modResults.contents.includes(flatDirSnippet)) {
      const allProjectsRegex = /allprojects\s*\{[\s\S]*?repositories\s*\{/;

      if (allProjectsRegex.test(config.modResults.contents)) {
        config.modResults.contents = config.modResults.contents.replace(
          allProjectsRegex,
          (match) => `${match}\n        ${flatDirSnippet}`
        );
      }
    }

    return config;
  });

  // -- Android: app/build.gradle --
  config = withAppBuildGradle(config, (config) => {
    let contents = config.modResults.contents;

    // Add unityLibrary dependency
    const depSnippet = `implementation project(':unityLibrary')`;
    if (!contents.includes(depSnippet)) {
      const depsRegex = /dependencies\s*\{/;
      if (depsRegex.test(contents)) {
        contents = contents.replace(
          depsRegex,
          (match) => `${match}\n    ${depSnippet}`
        );
      }
    }

    // Add NDK abiFilters
    const abiSnippet = `ndk { abiFilters 'armeabi-v7a', 'arm64-v8a' }`;
    if (!contents.includes('abiFilters')) {
      const defaultConfigRegex = /defaultConfig\s*\{/;
      if (defaultConfigRegex.test(contents)) {
        contents = contents.replace(
          defaultConfigRegex,
          (match) => `${match}\n        ${abiSnippet}`
        );
      }
    }

    config.modResults.contents = contents;
    return config;
  });

  return config;
};

module.exports = withExpoUnity;
