# Fragment Storage — Client 1.10.19

Client 1.10.19 is paired with Main App 0.5.34.

The Main App now reports the setting from the same `/efs/video.properties` keys used by the supplied stock Camera 5.18.11 APK whenever Pilot OS permissions allow read access. The Client therefore displays the concrete Main value (for example `4 GB`) without depending on the unsupported `camera.getOptions` operation.

If Pilot OS denies the Main App access to `/efs/video.properties`, the Client displays that permission/path diagnostic. After a real fragment rollover, Main may still report a size/time-derived value such as `4 GB (observed)`. Transfer of completed fragments is independent from whether the setting itself can be read.
