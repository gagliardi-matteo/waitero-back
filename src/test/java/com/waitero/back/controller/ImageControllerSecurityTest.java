package com.waitero.back.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageControllerSecurityTest {
    @Test void untrustedImageNamesMustNeverEscapeTheUploadsDirectory() throws Exception {
        ImageController controller = new ImageController();
        for (String name : new String[]{"../outside.jpg", "..\\outside.jpg", "/absolute.png", "G:\\outside.jpg", "..", ".", "bad\u0000.png"}) {
            assertEquals(400, controller.getImage(name).getStatusCode().value(), name);
        }
    }

    @Test void missingImageMustReturnControlledNotFound() throws Exception {
        assertEquals(404, new ImageController().getImage("campaign-nonexistent-6d31a64b-33e5-4627-86d2-34665a102a97.png").getStatusCode().value());
    }
}
