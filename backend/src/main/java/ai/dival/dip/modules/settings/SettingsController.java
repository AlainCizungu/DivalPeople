package ai.dival.dip.modules.settings;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rules this deployment runs by.
 *
 * <p>Open to anybody signed in, and deliberately so. Nothing here is one operator's data — it is
 * how long records are kept, what may be declared, how often anybody may ask, and what the two
 * published models turn on. Those are the terms everybody using the platform is subject to, and a
 * rule you are subject to and cannot read is not much of a rule.
 *
 * <p>It also has an unglamorous use: when somebody reports that a record vanished sooner than they
 * expected, this is the page that answers it in one look instead of a conversation with whoever
 * deployed the application.
 *
 * <p>No write endpoint, and none is coming behind it without a design. Shortening a retention
 * period puts records past due the moment it is saved; that is a decision with an audit entry and
 * a migration behind it, not a text box.
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public SettingsService.Settings effective() {
        return settings.effective();
    }
}
