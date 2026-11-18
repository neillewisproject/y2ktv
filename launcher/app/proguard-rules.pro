# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# HomePressSensor hears Home through View's hidden onCloseSystemDialogs, which only the system calls. Nothing in
# the app calls it, so R8 removed it and the triple-Home failsafe never counted a press.
-keepclassmembers class com.gathin.tvhome.HomePressSensor {
    public void onCloseSystemDialogs(java.lang.String);
}
