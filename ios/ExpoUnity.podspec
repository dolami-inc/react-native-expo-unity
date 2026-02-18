require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

# Resolve the Unity build artifacts directory.
# Pod::Config.instance.project_root points to the ios/ folder (where the Podfile lives),
# so we go one level up (..) to reach the React Native project root, then into unity/builds/ios/.
# Override with EXPO_UNITY_PATH environment variable.
rn_project_root = File.expand_path('..', Pod::Config.instance.project_root.to_s)
unity_ios_dir = ENV['EXPO_UNITY_PATH'] || File.join(rn_project_root, 'unity', 'builds', 'ios')

Pod::Spec.new do |s|
  s.name           = 'ExpoUnity'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description'] + '.'
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = { :ios => '15.1' }
  s.source         = { :git => package['repository']['url'], :tag => "v#{s.version}" }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.source_files = '**/*.{h,m,mm,swift}'

  # Unity build artifacts are referenced via xcconfig values using their absolute path.
  # We intentionally avoid vendored_frameworks / vendored_libraries because:
  #   1. CocoaPods requires those to be relative paths inside the pod source.
  #   2. The pod source may live in a read-only package manager cache (e.g. bun),
  #      making file copies impossible.
  # xcconfig values accept absolute paths and are passed through to Xcode as-is.
  framework_exists = File.exist?(File.join(unity_ios_dir, 'UnityFramework.framework'))
  a_files          = Dir.glob(File.join(unity_ios_dir, '*.a'))

  unity_ldflags  = []
  unity_ldflags << '-framework UnityFramework' if framework_exists
  unity_ldflags += a_files.map { |f| "\"#{f}\"" }

  # Unity framework is ARM-only (device build) — never link it for Simulator.
  # The [sdk=iphoneos*] conditional ensures these settings only apply when
  # building for a physical device, not the Simulator SDK.
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS'                        => "\"#{unity_ios_dir}/UnityFramework.framework/Headers\"",
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]'      => "\"#{unity_ios_dir}\"",
    'OTHER_LDFLAGS[sdk=iphoneos*]'               => unity_ldflags.join(' '),
    'CLANG_CXX_LANGUAGE_STANDARD'                => 'c++17',
    'GCC_PREPROCESSOR_DEFINITIONS'               => 'UNITY_FRAMEWORK=1',
    'ENABLE_BITCODE'                             => 'NO'
  }

  s.user_target_xcconfig = {
    'ENABLE_BITCODE'                         => 'NO',
    'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]'  => "\"#{unity_ios_dir}\"",
    'LIBRARY_SEARCH_PATHS[sdk=iphoneos*]'    => "\"#{unity_ios_dir}\"",
    'OTHER_LDFLAGS[sdk=iphoneos*]'           => unity_ldflags.join(' ')
  }
end
