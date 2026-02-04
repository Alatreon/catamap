package com.brewdog.catamap.ui.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.brewdog.catamap.R

/**
 * Adapter pour le ViewPager2 de l'onboarding
 * Gère l'affichage des slides
 */
class OnboardingAdapter(
    private val slides: List<OnboardingSlide>
) : RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_onboarding_slide, parent, false)
        return OnboardingViewHolder(view)
    }

    override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
        holder.bind(slides[position])
    }

    override fun getItemCount(): Int = slides.size

    /**
     * ViewHolder pour une slide d'onboarding
     */
    class OnboardingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.slideIcon)
        private val titleView: TextView = itemView.findViewById(R.id.slideTitle)
        private val descriptionView: TextView = itemView.findViewById(R.id.slideDescription)
        private val scrollView: NestedScrollView = itemView.findViewById(R.id.slideScrollView)

        fun bind(slide: OnboardingSlide) {
            iconView.setImageResource(slide.iconRes)
            titleView.text = slide.title
            descriptionView.text = slide.description
            
            // Remettre le scroll en haut à chaque affichage
            scrollView.scrollTo(0, 0)
        }
    }
}
