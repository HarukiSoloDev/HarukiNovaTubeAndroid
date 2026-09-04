# Haruki NovaTube Android — Release Signing Guide

For development builds, Android Studio's debug signing key is fine. For any public release, create and safely back up a dedicated `.jks` keystore.

To allow future APKs to update the installed app normally:
1. Keep the application ID `com.harukisolodev.harukistream` unless you intentionally want a fresh install identity.
2. Sign every release with the same release keystore.
3. Increase `versionCode` for every release.
4. Back up the keystore and passwords in more than one secure location.

Current version: 0.7.0 (versionCode 710).
