package com.brewdog.catamap.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.brewdog.catamap.R
import com.brewdog.catamap.ui.activities.MainActivity
import com.tbuonomo.viewpagerdotsindicator.DotsIndicator

/**
 * Activité d'onboarding full-screen
 * Présente les slides d'introduction à l'application
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var dotsIndicator: DotsIndicator
    private lateinit var startButton: Button
    private lateinit var onboardingManager: OnboardingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Initialiser le manager
        onboardingManager = OnboardingManager(this)

        // Initialiser les vues
        initViews()
        
        // Configurer le ViewPager
        setupViewPager()
        
        // Configurer le bouton
        setupStartButton()
    }

    /**
     * Initialise les vues
     */
    private fun initViews() {
        viewPager = findViewById(R.id.onboardingViewPager)
        dotsIndicator = findViewById(R.id.dotsIndicator)
        startButton = findViewById(R.id.startButton)
    }

    /**
     * Configure le ViewPager avec l'adapter
     */
    private fun setupViewPager() {
        val adapter = OnboardingAdapter(getOnboardingSlides())
        viewPager.adapter = adapter
        
        // Lier l'indicateur au ViewPager
        dotsIndicator.attachTo(viewPager)
        
        // Écouter les changements de page
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Afficher le bouton uniquement sur la dernière slide
                val isLastSlide = position == adapter.itemCount - 1
                startButton.visibility = if (isLastSlide) View.VISIBLE else View.GONE
            }
        })
    }

    /**
     * Configure le bouton "Commencer"
     */
    private fun setupStartButton() {
        startButton.setOnClickListener {
            // Marquer l'onboarding comme complété
            onboardingManager.markOnboardingCompleted()
            
            // Lancer MainActivity
            startMainActivity()
        }
    }

    /**
     * Lance la MainActivity et ferme l'onboarding
     */
    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Empêche le retour à l'onboarding
    }

    /**
     * Crée la liste des slides d'onboarding
     */
    private fun getOnboardingSlides(): List<OnboardingSlide> {
        return listOf(
            OnboardingSlide(
                iconRes = R.drawable.ic_legal,
                title = getString(R.string.onboarding_slide1_title),
                description = getString(R.string.onboarding_slide1_description)
            ),
            OnboardingSlide(
                iconRes = R.drawable.ic_warning,
                title = getString(R.string.onboarding_slide2_title),
                description = getString(R.string.onboarding_slide2_description)
            ),
            OnboardingSlide(
                iconRes = R.drawable.ic_map,
                title = getString(R.string.onboarding_slide3_title),
                description = getString(R.string.onboarding_slide3_description)
            ),
            OnboardingSlide(
                iconRes = R.drawable.ic_heart,
                title = getString(R.string.onboarding_slide4_title),
                description = getString(R.string.onboarding_slide4_description)
            )
        )
    }

    /**
     * Désactiver le bouton retour
     */
    override fun onBackPressed() {
        // Ne rien faire - empêche de quitter l'onboarding accidentellement
    }
}
