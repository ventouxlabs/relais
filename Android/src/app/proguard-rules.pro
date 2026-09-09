# R8 / ProGuard rules for Relais.
#
# NOTE: `buildTypes.release` has referenced this filename since the fork, but the file did not exist
# until now — with `isMinifyEnabled = false` a missing proguardFiles entry is inert, so nothing ever
# complained. Anything below therefore only starts mattering when minification is switched on.

# ---------------------------------------------------------------------------------------------
# llmedge's HuggingFace downloader — genuinely absent from the classpath, deliberately.
# ---------------------------------------------------------------------------------------------
# build.gradle.kts excludes `io.ktor` from the llmedge dependency: Relais provisions models itself
# via ModelSpec.localFile and never uses llmedge's HF download path. That leaves HFModels referencing
# Ktor classes that are not on the classpath — inert at runtime (the code is never reached) but a
# hard R8 build error. These are exactly the rules R8 generated in missing_rules.txt.
-dontwarn io.ktor.client.engine.okhttp.OkHttp
-dontwarn io.ktor.client.engine.okhttp.OkHttpConfig
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$Config
-dontwarn io.ktor.client.plugins.contentnegotiation.ContentNegotiation$Plugin
-dontwarn io.ktor.serialization.kotlinx.json.JsonSupportKt

# Same story for llmedge's other excluded/optional transitives, surfaced once the keep rule below
# brought its full class surface into R8's view:
#   - JP2Decoder: an optional JPEG-2000 codec referenced by pdfbox-android (an llmedge transitive
#     that the image-gen path never touches).
#   - SentenceEmbedding: from `sentence-embeddings`, excluded in build.gradle.kts because Relais has
#     its own embedder.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.ml.shubham0204.sentence_embeddings.SentenceEmbedding

# ---------------------------------------------------------------------------------------------
# Gson reflection targets — the highest-risk surface in this app.
# ---------------------------------------------------------------------------------------------
# Gson binds JSON to fields BY NAME via reflection and ships no rules to keep *our* model classes.
# Most fields here carry no @SerializedName (see AllowedModel: name/modelId/modelFile/commitHash/…),
# so the usual "keep @SerializedName fields" idiom is NOT sufficient — R8 would rename the rest and
# every one would silently deserialize to null. The upstream allowlist parse is the boot path for
# model provisioning, so that failure mode is a dead node with no useful error.
#
# The codebase already documents that Gson does not honour Kotlin non-null types (a partial object
# decodes with null "non-null" fields); renaming would turn that latent hazard into the normal case.
-keep class cc.grepon.relais.data.** { *; }
-keep class cc.grepon.relais.RelaisHuggingFace$* { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------------------------
# protobuf-javalite — found the hard way, as a launch crash.
# ---------------------------------------------------------------------------------------------
# Generated message classes expose fields with a trailing underscore (`theme_`) that the javalite
# runtime resolves REFLECTIVELY. R8 renames them and the runtime throws at class init:
#
#   java.lang.RuntimeException: Field theme_ for w3.C not found.
#     Known fields are [public int w3.C.e, public F5.A w3.C.f, …]
#
# which surfaces as "Unable to create application cc.grepon.relais.RelaisApplication" — the whole
# app dies on launch, not the proto path. protobuf ships no consumer rules for this.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ---------------------------------------------------------------------------------------------
# JNI — native code resolves these by name; R8 cannot see the reference.
# ---------------------------------------------------------------------------------------------
-keepclasseswithmembernames class * {
  native <methods>;
}

# The rule above only protects classes that DECLARE native methods. It does not protect the plain
# Java/Kotlin config and result types that native code reaches back into via JNI — those look
# entirely unused to R8. Found the hard way: the node started and reported ready:true, then every
# inference died with
#
#   java.lang.NoSuchMethodError: no non-static method
#     "Lcom/google/ai/edge/litertlm/SamplerConfig;.getTopK()I"
#
# because R8 removed the getter litertlm's .so calls. Keep the whole Java surface of every
# native-backed engine — none of them ship consumer rules, and each missing one is a runtime
# failure that only shows up under real inference, never in a JVM test.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class io.aatricks.llmedge.** { *; }

# ---------------------------------------------------------------------------------------------
# BouncyCastle — the per-node CA and the SAN'd leaf (feature-18).
# ---------------------------------------------------------------------------------------------
# There were ZERO BouncyCastle rules here before this feature, and the pre-feature cert path
# happened to survive minification because it touched only a handful of concrete classes.
#
# NOTE ON WHY, because the obvious explanation is wrong and would send you down a blind alley:
# it is NOT that R8 strips the SHA256withECDSA implementation. That algorithm resolves through the
# platform's Conscrypt provider, which lives in the framework and which R8 cannot touch. The
# reflection that actually matters is inside bcpkix's own algorithm-finder machinery —
# DefaultSignatureAlgorithmIdentifierFinder and the operator helpers — which look classes up by
# name. That is what a narrow keep would break, and it breaks release-only.
#
# `-dontwarn` is necessary but blunt: it equally silences warnings about classes genuinely missing
# because a keep was narrowed too far, so it is the thing most likely to HIDE an R8 failure at
# build time. Anyone narrowing must not trust a quiet build.
#
# IF YOU NARROW THIS: try org.bouncycastle.jcajce.**, .operator.**, .cert.** and .asn1.x509.**
# first, and re-run CertTrustProbe on a RELEASE build — exercising the RE-MINT path, not just the
# first mint, since mintLeaf and mintCa reach different BC code and a stripped class in the re-mint
# path only surfaces after a network change. A debug build and a JVM test both prove nothing here.
#
# Per this repo's history with R8 (every keep rule above was earned from a real on-device failure,
# and CI runs no R8 whatsoever), these are deliberately broad. The reviewer explicitly did NOT flag
# the broad keep as a vulnerability — Relais never deserializes untrusted class names — so this is
# a size/hygiene matter, not a security one. UNVERIFIED as of writing: no release APK has been
# built with them.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
