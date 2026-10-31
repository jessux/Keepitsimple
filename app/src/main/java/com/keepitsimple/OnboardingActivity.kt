package com.keepitsimple

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton

/** Présentation affichée au premier lancement (et sur demande : « aide »). */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val KEY = "onboarded"
        fun done(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean(KEY, false)
        private fun markDone(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    private data class Page(val emoji: String, val title: String, val body: String)

    private val pages = listOf(
        Page("👋", "Bienvenue dans KIS",
            "Votre pense-bête 100 % à la voix.\n\nPas de clavier, pas de bouton : l'application vous écoute dès qu'elle est ouverte. Parlez, KIS note."),
        Page("🎤", "Parlez naturellement",
            "« Rappelle-moi d'appeler Paul demain à 18h »\n\n« Payer la facture dans 3 jours »\n\n« Sortir les poubelles tous les lundis à 20h »\n\nKIS comprend la date, l'heure et les répétitions."),
        Page("✅", "Gérez vos tâches",
            "Chaque tâche a un numéro.\n\n« J'ai fini la 3 » · « Supprime la 2 » · « Lis mes tâches »\n\nOu du doigt : glisser → terminé, ← supprimer, toucher pour modifier."),
        Page("🔔", "Rappels et synchronisation",
            "Une notification arrive à l'échéance.\n\nDites « connecte Google » pour retrouver vos tâches dans Google Tasks et recevoir chaque matin un e-mail des tâches en retard."),
        Page("🔒", "Deux autorisations",
            "Le micro, pour vous écouter : uniquement quand KIS est ouvert.\n\nLes notifications, pour vos rappels.\n\nDites « pause » pour couper l'écoute, « ferme l'application » pour quitter, « aide » pour revoir cette présentation.")
    )

    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var next: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        pager = findViewById(R.id.pager)
        dots = findViewById(R.id.dots)
        next = findViewById(R.id.next)

        pager.adapter = PageAdapter()
        pages.indices.forEach { _ ->
            dots.addView(View(this).apply {
                background = ContextCompat.getDrawable(this@OnboardingActivity, R.drawable.circle)
                val size = (8 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = size / 2; marginEnd = size / 2 }
            })
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = update(position)
        })
        update(0)

        findViewById<MaterialButton>(R.id.skip).setOnClickListener { finishOnboarding() }
        next.setOnClickListener {
            if (pager.currentItem < pages.lastIndex) pager.currentItem += 1 else finishOnboarding()
        }
    }

    private fun update(position: Int) {
        val on = ContextCompat.getColor(this, R.color.accent)
        val off = ContextCompat.getColor(this, R.color.paused)
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).backgroundTintList = ColorStateList.valueOf(if (i == position) on else off)
        }
        next.text = if (position == pages.lastIndex) "C'est parti" else "Suivant"
    }

    private fun finishOnboarding() {
        val first = !done(this)
        markDone(this)
        // Premier lancement : on ouvre l'écran principal ; sinon on y revient simplement
        if (first) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val emoji: TextView = v.findViewById(R.id.emoji)
        val title: TextView = v.findViewById(R.id.pageTitle)
        val body: TextView = v.findViewById(R.id.pageBody)
    }

    private inner class PageAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(layoutInflater.inflate(R.layout.item_onboarding, parent, false))
        override fun onBindViewHolder(h: Holder, position: Int) {
            val p = pages[position]
            h.emoji.text = p.emoji
            h.title.text = p.title
            h.body.text = p.body
        }
    }
}
