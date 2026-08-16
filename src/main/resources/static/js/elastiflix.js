/*
 * Behaviour that used to live in inline on* attributes.
 *
 * Those attributes are why the Content-Security-Policy had to allow 'unsafe-inline'
 * scripts, which in turn is what made a `javascript:` URL in a movie document
 * dangerous. Everything here is wired from data-* attributes instead, so the policy
 * can be plain `script-src 'self'`.
 */
(function () {
    'use strict';

    /** Submit the owning form as soon as the control changes (filters, sort, view). */
    function submitOnChange(control) {
        control.addEventListener('change', function () {
            if (control.form) {
                control.form.submit();
            }
        });
    }

    /** Submit on Enter, for text inputs that would otherwise need the Search button. */
    function submitOnEnter(control) {
        control.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && control.form) {
                event.preventDefault();
                control.form.submit();
            }
        });
    }

    /**
     * Swap a poster that fails to load for the placeholder next to it.
     *
     * The old inline handler reached for `this.nextElementSibling`, but the placeholder
     * is only rendered when the movie has no poster at all — so on a broken image it hit
     * the gradient overlay instead and revealed that. The placeholder is now always in
     * the DOM, hidden by `.hidden`, and found by class rather than by position.
     */
    function showFallbackOnError(image) {
        image.addEventListener('error', function () {
            image.classList.add('hidden');
            var container = image.parentElement;
            var fallback = container && container.querySelector('.poster-fallback');
            if (fallback) {
                fallback.classList.remove('hidden');
            }
        });
    }

    document.querySelectorAll('[data-submit-on-change]').forEach(submitOnChange);
    document.querySelectorAll('[data-submit-on-enter]').forEach(submitOnEnter);
    document.querySelectorAll('[data-poster]').forEach(showFallbackOnError);
})();
