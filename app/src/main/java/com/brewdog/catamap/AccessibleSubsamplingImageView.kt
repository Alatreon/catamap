package com.brewdog.catamap

import android.content.Context
import android.util.AttributeSet
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

class AccessibleSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SubsamplingScaleImageView(context, attrs) {

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}