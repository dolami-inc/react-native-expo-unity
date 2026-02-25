const {
  withXcodeProject,
  withSettingsGradle,
  withProjectBuildGradle,
  withAppBuildGradle,
} = require('@expo/config-plugins');
const path = require('path');
const fs = require('fs');

/**
 * Expo Config Plugin for @dolami-inc/react-native-expo-unity.
 *
 * iOS:
 * - Injects required Xcode build settings (bitcode, C++17)
 * - Adds a build phase that embeds UnityFramework.framework into the app
 *   bundle at build time (device builds only)
 *
 * Android:
 * - Includes the :unityLibrary Gradle module in settings.gradle
 * - Adds flatDir repos for Unity's .aar/.jar libs in root build.gradle
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

  // -- Android: settings.gradle (include :unityLibrary module) --
  config = withSettingsGradle(config, (config) => {
    const projectRoot = config.modRequest.projectRoot;
    const androidUnityPath =
      options.androidUnityPath ||
      process.env.EXPO_UNITY_ANDROID_PATH ||
      path.join(projectRoot, 'unity', 'builds', 'android');

    const contents = config.modResults.contents;

    // Include :unityLibrary module
    const includeSnippet = `include ':unityLibrary'`;
    const projectSnippet = `project(':unityLibrary').projectDir = new File('${androidUnityPath}/unityLibrary')`;

    if (!contents.includes(includeSnippet)) {
      config.modResults.contents =
        contents +
        `\n// Unity as a Library\n${includeSnippet}\n${projectSnippet}\n`;
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
