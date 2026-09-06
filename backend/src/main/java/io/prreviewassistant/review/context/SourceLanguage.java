package io.prreviewassistant.review.context;

import java.util.Locale;

public enum SourceLanguage {
    JAVA, KOTLIN, JAVASCRIPT, TYPESCRIPT, PYTHON, GO, RUST, CSHARP, C_CPP, CONFIG, MARKUP, UNKNOWN;
    public static SourceLanguage fromPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".java")) return JAVA;
        if (p.endsWith(".kt") || p.endsWith(".kts")) return KOTLIN;
        if (p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") || p.endsWith(".cjs")) return JAVASCRIPT;
        if (p.endsWith(".ts") || p.endsWith(".tsx")) return TYPESCRIPT;
        if (p.endsWith(".py")) return PYTHON;
        if (p.endsWith(".go")) return GO;
        if (p.endsWith(".rs")) return RUST;
        if (p.endsWith(".cs")) return CSHARP;
        if (p.matches(".*\\.(c|cc|cpp|cxx|h|hpp)$")) return C_CPP;
        if (p.matches(".*(pom\\.xml|build\\.gradle|settings\\.gradle|package\\.json|tsconfig\\.json|pyproject\\.toml|requirements\\.txt|dockerfile|compose\\.ya?ml)$")) return CONFIG;
        if (p.matches(".*\\.(md|mdx|html|css|adoc|rst)$")) return MARKUP;
        return UNKNOWN;
    }
}
