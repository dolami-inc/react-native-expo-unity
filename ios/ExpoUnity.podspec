require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

# Resolve the Unity build artifacts directory.
# By default, looks for `unity/builds/ios/` in the app's project root.
# Override with EXPO_UNITY_PATH environment variable.
unity_ios_dir = ENV['EXPO_UNITY_PATH'] || File.join(Pod::Config.instance.project_root.to_s, 'unity', 'builds', 'ios')

Pod::Spec.new do |s|
  s.name           = 'ExpoUnity'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = { :ios => '15.1' }
  s.source         = { :git => package['repository']['url'], :tag => "v#{s.version}" }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.source_files = '**/*.{h,m,mm,swift}'
  s.exclude_files = 'UnityFramework.framework/**/*'

  # Copy Unity build artifacts from the app's project into the pod at install time
  s.prepare_command = <<-CMD
    if [ -d "#{unity_ios_dir}" ]; then
      cp -R "#{unity_ios_dir}/UnityFramework.framework" . 2>/dev/null || true
    fi
  CMD

  # Link UnityFramework if present
  if File.exist?(File.join(unity_ios_dir, 'UnityFramework.framework'))
    s.vendored_frameworks = 'UnityFramework.framework'
  end

  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => [
      '"${PODS_TARGET_SRCROOT}/UnityFramework.framework/Headers"',
      "\"#{unity_ios_dir}/UnityFramework.framework/Headers\""
    ].join(' '),
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'GCC_PREPROCESSOR_DEFINITIONS' => 'UNITY_FRAMEWORK=1',
    'ENABLE_BITCODE' => 'NO'
  }

  s.user_target_xcconfig = {
    'ENABLE_BITCODE' => 'NO'
  }
end
