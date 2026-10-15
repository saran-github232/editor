# Clipper privacy policy draft

This draft describes the current development build. The publisher must add a real support contact, legal publisher name, effective date and public policy URL before release.

Clipper reads only video files selected by the user through Android's document picker. It stores project settings, selected-file access, subtitles and exported clips on the device. It does not include advertising, analytics, crash-reporting SDKs or a remote video-processing service.

Automatic speech recognition runs on the device. A selected speech model is downloaded from `alphacephei.com`; that host receives ordinary network information, including the device's public IP address, during the download. Audio and transcripts are not sent to that host.

Google Drive upload is optional. When a user connects Drive, Google handles account authorization. Clipper requests the `drive.file` scope, which allows access to files created or explicitly opened through this app. Clipper uploads the requested output videos, filenames and matching subtitle files into a project folder in that account. Google processes this data under its own terms and privacy policy. Access tokens are held in memory, not written to project files or logs.

Users can delete local projects from the library. This does not delete the original source video, copies saved outside the app, or uploaded Google Drive files. Drive files can be deleted in Google Drive. Users can revoke authorization from Clipper Settings or their Google account's connected-app controls.

Uninstalling Clipper removes app-private project data, exported files and language models. Files saved outside the app and Drive uploads remain under the user's control. Device backup/transfer of app-private content is disabled in the Android manifest and backup rules.

The publisher must keep this policy and Google Play Data safety disclosures consistent with the final app, including any SDKs or hosted services added later.
