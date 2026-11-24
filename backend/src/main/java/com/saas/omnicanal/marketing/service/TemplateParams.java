package com.saas.omnicanal.marketing.service;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Variables {{n}} de una plantilla de WhatsApp. En marketing solo se admite {{1}}, que se llena con el nombre del cliente. */
final class TemplateParams {
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(\\d+)\\s*}}");

    private TemplateParams() {}

    static int count(String componentsJson) {
        if (componentsJson == null) return 0;
        var found = new HashSet<String>();
        var m = VAR.matcher(componentsJson);
        while (m.find()) found.add(m.group(1));
        return found.size();
    }

    static List<String> forName(String componentsJson, String fullName) {
        if (count(componentsJson) == 0) return List.of();
        String first = fullName == null || fullName.isBlank() ? "" : fullName.strip().split("\\s+")[0];
        return List.of(first.isEmpty() ? "cliente" : first);
    }
}
