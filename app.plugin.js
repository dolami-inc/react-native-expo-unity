const { withXcodeProject } = require('@expo/config-plugins');
const path = require('path');
const fs = require('fs');

/**
 * Expo Config Plugin for @dolami-inc/react-native-expo-unity.
 *
 * - Injects required Xcode build settings (bitcode, C++17)
 * - Adds a build phase that embeds UnityFramework.framework into the app
 *   bundle at build time (device builds only)
 *
 * @param {object} config - Expo config
 * @param {{ unityPath?: string }} options
 *   unityPath — absolute path to the Unity iOS build artifacts directory.
 *   Defaults to `<projectRoot>/unity/builds/ios`.
 *   Can also be set via the EXPO_UNITY_PATH environment variable.
 */
const withExpoUnity = (config, options = {}) => {
  return withXcodeProject(config, (config) => {
    const xcodeProject = config.modResults;
    const projectRoot = config.modRequest.projectRoot;

    const unityPath =
      options.unityPath ||
      process.env.EXPO_UNITY_PATH ||
      path.join(projectRoot, 'unity', 'builds', 'ios');

    // -- Build settings --
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

    // -- Embed UnityFramework via build script phase --
    // UnityFramework is a dynamic framework that must be embedded (copied)
    // into the app bundle's Frameworks/ directory, otherwise dyld fails at
    // launch. We use a shell script build phase instead of vendored_frameworks
    // because the pod source may live in a read-only package manager cache.
    const frameworkSrc = path.join(unityPath, 'UnityFramework.framework');
    if (fs.existsSync(frameworkSrc)) {
      const target = xcodeProject.getFirstTarget();

      // Shell script that copies and codesigns the framework (device only).
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
};

module.exports = withExpoUnity;
