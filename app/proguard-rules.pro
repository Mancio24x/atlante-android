# Atlante's own code is kept whole: only unused library code is removed.
-keep class app.atlante.library.** { *; }
# org.json is part of Android; XmlPull parsers come from the platform.
-dontwarn org.xmlpull.v1.**
