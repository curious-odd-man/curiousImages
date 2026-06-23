package com.github.curiousoddman.curious_images.ui.controller.custom;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// AI Generated
@Component
public class MusicCurveAnimation {
    private static final Random RND = new Random();

    private static final String[]       NOTES = {"♩", "♪", "♫", "♬", "𝄞"};
    private              AnimationTimer timer;

    enum OriginSide {LEFT, RIGHT}

    static class Curve {
        private final double width;
        private final double height;
        OriginSide originSide;
        double     progress;       // 0.0 → 1.0: traveling; 1.0 → 2.0: fading out
        double     speed;
        Color      color;

        // Bezier control points
        double x0, y0;         // start
        double cx1, cy1;       // control 1
        double cx2, cy2;       // control 2
        double x1, y1;         // end

        // Note state
        boolean hasNote;
        double  noteT;          // where on curve (0-1) the note sits
        double  noteLife;       // countdown to remove note
        String  noteSymbol;

        Curve(double width, double height, OriginSide originSide) {
            this.width = width;
            this.height = height;
            this.originSide = originSide;
            this.speed = 0.001 + RND.nextDouble() * 0.004;
            this.color = Color.hsb(RND.nextDouble() * 360, 0.7 + RND.nextDouble() * 0.3, 0.85 + RND.nextDouble() * 0.15);
            this.progress = 0;
            initPoints();
        }

        void initPoints() {
            double midX = width * (0.2 + RND.nextDouble() * 0.6);
            double midY = height * (0.2 + RND.nextDouble() * 0.6);

            switch (originSide) {
                case LEFT -> {
                    x0 = 0;
                    y0 = height * RND.nextDouble();
                    x1 = width;
                    y1 = height * RND.nextDouble();
                }
                case RIGHT -> {
                    x0 = width;
                    y0 = height * RND.nextDouble();
                    x1 = 0;
                    y1 = height * RND.nextDouble();
                }
            }

            // Control points create organic S-curve feel
            cx1 = x0 + (midX - x0) * (0.4 + RND.nextDouble() * 0.4) + (RND.nextDouble() - 0.5) * 200;
            cy1 = y0 + (midY - y0) * (0.4 + RND.nextDouble() * 0.4) + (RND.nextDouble() - 0.5) * 200;
            cx2 = x1 + (midX - x1) * (0.4 + RND.nextDouble() * 0.4) + (RND.nextDouble() - 0.5) * 200;
            cy2 = y1 + (midY - y1) * (0.4 + RND.nextDouble() * 0.4) + (RND.nextDouble() - 0.5) * 200;
        }

        // Evaluate cubic bezier point at t
        double[] pointAt(double t) {
            double mt = 1 - t;
            double x  = mt * mt * mt * x0 + 3 * mt * mt * t * cx1 + 3 * mt * t * t * cx2 + t * t * t * x1;
            double y  = mt * mt * mt * y0 + 3 * mt * mt * t * cy1 + 3 * mt * t * t * cy2 + t * t * t * y1;
            return new double[]{x, y};
        }

        boolean isAlive() {
            return progress < 2.0;
        }

        double opacity() {
            if (progress <= 1.0) {
                return 1.0;
            }
            return Math.max(0, 1.0 - (progress - 1.0)); // fade out over second half
        }

        void spawnNote() {
            hasNote = true;
            noteT = 0.1 + RND.nextDouble() * 0.8;
            noteLife = 2.5;
            noteSymbol = NOTES[RND.nextInt(NOTES.length)];
        }
    }

    List<Curve> curves    = new ArrayList<>();
    long        lastSpawn = 0;
    long        lastNote  = 0;

    void spawnCurve(double width, double height) {
        OriginSide[] originSides = OriginSide.values();
        curves.add(new Curve(width, height, originSides[RND.nextInt(originSides.length)]));
    }

    void update(long now, double width, double height) {
        // Spawn new curves periodically, keep 3-5 per side roughly
        if (now - lastSpawn > 1_200_000_000L && curves.size() < 20) {
            spawnCurve(width, height);
            lastSpawn = now;
        }

        // Periodically spawn notes on random curves
        if (now - lastNote > 800_000_000L) {
            curves.stream()
                  .filter(c -> c.progress < 1.0 && !c.hasNote)
                  .findAny()
                  .ifPresent(Curve::spawnNote);
            lastNote = now;
        }

        double dt = 0.016; // ~60fps

        curves.forEach(c -> {
            c.progress += c.speed;
            if (c.hasNote) {
                c.noteLife -= dt;
                if (c.noteLife <= 0) {
                    c.hasNote = false;
                }
            }
        });

        curves.removeIf(c -> !c.isAlive());

        // Ensure minimum curve count
        while (curves.size() < 8) {
            spawnCurve(width, height);
        }
    }

    void draw(GraphicsContext gc, double width, double height) {
        // Dark background with subtle fade trail
        gc.setFill(Color.rgb(10, 10, 18, 0.25));
        gc.fillRect(0, 0, width, height);

        for (Curve c : curves) {
            double opacity = c.opacity();
            double t       = Math.min(c.progress, 1.0); // draw up to current progress

            // Draw the curve segment from 0 → t using polyline approximation
            int      segments = 80;
            double[] xs       = new double[segments + 1];
            double[] ys       = new double[segments + 1];

            for (int i = 0; i <= segments; i++) {
                double   u  = (i / (double) segments) * t;
                double[] pt = c.pointAt(u);
                xs[i] = pt[0];
                ys[i] = pt[1];
            }

            // Glow pass (wider, low opacity)
            gc.setStroke(c.color.deriveColor(0, 1, 1, opacity * 0.25));
            gc.setLineWidth(6);
            gc.strokePolyline(xs, ys, segments + 1);

            // Core line
            gc.setStroke(c.color.deriveColor(0, 1, 1, opacity * 0.9));
            gc.setLineWidth(2);
            gc.strokePolyline(xs, ys, segments + 1);

            // Leading dot
            if (c.progress <= 1.0) {
                double[] tip = c.pointAt(t);
                gc.setFill(Color.WHITE.deriveColor(0, 1, 1, opacity * 0.9));
                gc.fillOval(tip[0] - 3, tip[1] - 3, 6, 6);
            }

            // Musical note
            if (c.hasNote) {
                double[] npt         = c.pointAt(c.noteT);
                double   noteOpacity = Math.min(1.0, c.noteLife / 0.5) * opacity;
                double   bounce      = Math.sin(c.noteLife * 6) * 3; // subtle bounce

                gc.setFill(Color.WHITE.deriveColor(0, 1, 1, noteOpacity));
                gc.setFont(Font.font("serif", FontWeight.BOLD, 20));
                gc.fillText(c.noteSymbol, npt[0] + 6, npt[1] - 8 + bounce);

                // Small halo behind note
                gc.setStroke(c.color.deriveColor(0, 1, 1.2, noteOpacity * 0.5));
                gc.setLineWidth(1);
                gc.strokeOval(npt[0] - 2, npt[1] - 14, 24, 24);
            }
        }
    }
}