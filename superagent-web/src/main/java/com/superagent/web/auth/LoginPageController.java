package com.superagent.web.auth;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in page at its clean URL. Someone already signed in has nothing to do there, so they go straight to the
 * app; everyone else gets the static page.
 */
@Controller
public class LoginPageController {

    @GetMapping("/login")
    public String login(Authentication auth) {
        boolean signedIn = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        return signedIn ? "redirect:/" : "forward:/login.html";
    }
}
