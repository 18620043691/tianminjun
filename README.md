# BitChat Android and iOS builds

This repository contains the Android offline BLE mesh chat implementation and GitHub Actions packaging workflow.

The workflow builds an Android debug APK and creates an unsigned iOS IPA by checking out the iOS app source from the public `18620043691/bitchat` fork. The IPA must be signed with a compatible Apple provisioning profile before installing on a device.

Open the **Actions** tab and run **Build install packages**. Download the APK and IPA from the completed workflow run's **Artifacts** section.