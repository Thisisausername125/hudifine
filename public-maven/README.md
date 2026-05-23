# Hudifine Public Maven Repository

This directory hosts published Maven artifacts for Hudifine API.

Recommended Maven repository URL (CDN-backed):

```text
https://cdn.jsdelivr.net/gh/Pacsy1/hudifine@main/public-maven/
```

Raw GitHub fallback URL:

```text
https://raw.githubusercontent.com/Pacsy1/hudifine/main/public-maven/
```

Note: `raw.githubusercontent.com` returns `404` for folder URLs opened directly in a browser.
It serves files, not directory listings. File URLs are valid and public.

Example file URLs:

```text
https://raw.githubusercontent.com/Pacsy1/hudifine/main/public-maven/dev/hudifine/hudifine-api/maven-metadata.xml
https://raw.githubusercontent.com/Pacsy1/hudifine/main/public-maven/dev/hudifine/hudifine-api/1.0.0/hudifine-api-1.0.0.pom
```

Dependency example:

```groovy
repositories {
    maven { url = uri("https://cdn.jsdelivr.net/gh/Pacsy1/hudifine@main/public-maven/") }
}

dependencies {
    modImplementation "dev.hudifine:hudifine-api:1.0.0"
}
```
