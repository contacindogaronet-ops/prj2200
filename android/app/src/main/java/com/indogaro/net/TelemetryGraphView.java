package com.indogaro.net;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class TelemetryGraphView extends View {
    private Paint linePaint;
    private Path path;
    private List<Float> data = new ArrayList<>();
    private int maxDataPoints = 60; // Menyimpan 60 detik (1 Menit) riwayat

    public TelemetryGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint = new Paint();
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(5f);
        linePaint.setAntiAlias(true);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        path = new Path();
    }

    public void setLineColor(String hexColor) {
        linePaint.setColor(Color.parseColor(hexColor));
    }

    public void addDataPoint(float value) {
        data.add(value);
        if (data.size() > maxDataPoints) {
            data.remove(0);
        }
        invalidate(); // Paksa GPU untuk menggambar ulang
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data.isEmpty()) return;

        float width = getWidth();
        float height = getHeight();
        float max = 0;
        
        // Cari nilai tertinggi untuk skala dinamis
        for (float v : data) if (v > max) max = v;
        if (max == 0) max = 1; 

        path.reset();
        float stepX = width / (maxDataPoints - 1);
        
        for (int i = 0; i < data.size(); i++) {
            float x = i * stepX;
            // Sisakan 10% padding di atas grafik
            float y = height - ((data.get(i) / max) * height * 0.9f); 
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, linePaint);
    }
}
