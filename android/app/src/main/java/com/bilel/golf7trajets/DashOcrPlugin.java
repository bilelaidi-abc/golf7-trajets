package com.bilel.golf7trajets;

import android.net.Uri;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/** Lit le texte d'une photo du tableau de bord, dans le téléphone et sans connexion (ML Kit). */
@CapacitorPlugin(name = "DashOcr")
public class DashOcrPlugin extends Plugin {

    @PluginMethod
    public void read(PluginCall call) {
        String path = call.getString("path");
        if (path == null) {
            call.reject("Photo introuvable.", "NO_PATH");
            return;
        }
        InputImage image;
        try {
            image = InputImage.fromFilePath(getContext(), Uri.parse(path));
        } catch (Exception e) {
            call.reject("Impossible d'ouvrir la photo.", "BAD_IMAGE", e);
            return;
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener(result -> {
                    JSArray lines = new JSArray();
                    for (Text.TextBlock block : result.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) lines.put(line.getText());
                    }
                    JSObject r = new JSObject();
                    r.put("text", result.getText());
                    r.put("lines", lines);
                    call.resolve(r);
                })
                .addOnFailureListener(e -> call.reject("Lecture de la photo impossible.", "OCR_FAILED", e));
    }
}
