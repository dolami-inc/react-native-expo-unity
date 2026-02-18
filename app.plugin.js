// @ts-check
const { withXcodeProject } = require('@expo/config-plugins');

/**
 * Expo Config Plugin for react-native-expo-unity.
 *
 * Automatically injects the Xcode build settings required for
 * Unity as a Library (UaaL) to link and run correctly.
 *
 * @param {import('@expo/config-plugins').ExpoConfig} config
 * @param {{ unityPath?: string }} options
 *   unityPath — path to the Unity iOS build artifacts directory.
 *   Defaults to `$(PROJECT_DIR)/unity/builds/ios`.
 *   Can also be set via the EXPO_UNITY_PATH environment variable.
 */
const withExpoUnity = (config, options = {}) => {
  return withXcodeProject(config, (config) => {
    const xcodeProject = config.modResults;

    const unityPath =
      options.unityPath ||
      process.env.EXPO_UNITY_PATH ||
      '$(PROJECT_DIR)/unity/builds/ios';

    const configurations = xcodeProject.pbxXCBuildConfigurationSection();

    for (const key of Object.keys(configurations)) {
      const configuration = configurations[key];
      if (typeof configuration !== 'object' || !configuration.buildSettings) {
        continue;
      }

      const settings = configuration.buildSettings;

      // Unity as a Library does not support bitcode.
      settings['ENABLE_BITCODE'] = 'NO';

      // Unity headers require C++17.
      settings['CLANG_CXX_LANGUAGE_STANDARD'] = 'c++17';

      // Add the Unity framework directory to the search paths so that
      // UnityFramework.framework can be found at build time.
      addFrameworkSearchPath(settings, unityPath);
    }

    return config;
  });
};

/**
 * Appends `unityPath` to FRAMEWORK_SEARCH_PATHS without duplicating it.
 *
 * The xcode npm package stores this setting as either:
 *   - undefined (not set)
 *   - a plain string: `"$(inherited)"`
 *   - a parenthesised list: `("$(inherited)", "/some/path")`
 *
 * @param {Record<string, any>} settings
 * @param {string} unityPath
 */
function addFrameworkSearchPath(settings, unityPath) {
  const quoted = `"${unityPath}"`;
  const existing = settings['FRAMEWORK_SEARCH_PATHS'];

  if (!existing) {
    settings['FRAMEWORK_SEARCH_PATHS'] = `(${quoted}, "$(inherited)")`;
    return;
  }

  const asStr = String(existing);

  // Already present — nothing to do.
  if (asStr.includes(unityPath)) {
    return;
  }

  // Strip surrounding parens if present, then rebuild.
  const inner = asStr.replace(/^\(|\)$/g, '').trim();
  settings['FRAMEWORK_SEARCH_PATHS'] = `(${quoted}, ${inner})`;
}

module.exports = withExpoUnity;
