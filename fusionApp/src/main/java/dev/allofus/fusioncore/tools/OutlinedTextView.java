package dev.allofus.fusioncore.tools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextPaint;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

public class OutlinedTextView extends AppCompatTextView {
    public OutlinedTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null) {
            TextPaint paint = getPaint();
            int color = paint.getColor();
            Paint.Style style = paint.getStyle();
            float width = paint.getStrokeWidth();
            Paint.Join join = paint.getStrokeJoin();
            int save = canvas.save();
            try {
                paint.setColor(Color.BLACK);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
                paint.setStrokeJoin(Paint.Join.ROUND);
                canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
                layout.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
                paint.setColor(color);
                paint.setStyle(style);
                paint.setStrokeWidth(width);
                paint.setStrokeJoin(join);
            }
        }
        super.onDraw(canvas);
    }
}