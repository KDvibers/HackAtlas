package com.hackatlas.moderation;

import java.net.URI;
import java.net.URISyntaxException;
import com.hackatlas.web.ApiFailure;

/** Syntax validation only: never resolves a host, follows a redirect, or downloads a URL. */
public final class SafeHttpsUrl {
    private SafeHttpsUrl() { }

    public static String validate(String value, String field) {
        try {
            URI uri = new URI(value).parseServerAuthority();
            if (!value.startsWith("https://") || !"https".equals(uri.getScheme())
                    || uri.isOpaque() || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getRawUserInfo() != null || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new URISyntaxException("", "Invalid HTTPS URL");
            }
            return value;
        } catch (URISyntaxException | NullPointerException exception) {
            throw new ApiFailure(400, "VALIDATION_ERROR", "Укажите корректную HTTPS-ссылку без логина и пароля.");
        }
    }
}
