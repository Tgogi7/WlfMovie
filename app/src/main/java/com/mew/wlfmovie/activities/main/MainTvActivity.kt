package com.mew.wlfmovie.activities.main

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.tanasi.navigation.widget.setupWithNavController
import com.mew.wlfmovie.BuildConfig
import com.mew.wlfmovie.R
import com.mew.wlfmovie.databinding.ActivityMainTvBinding
import com.mew.wlfmovie.databinding.ContentHeaderMenuMainTvBinding
import com.mew.wlfmovie.fragments.player.PlayerTvFragment
import com.mew.wlfmovie.ui.UpdateAppTvDialog
import com.mew.wlfmovie.providers.IptvProvider
import com.mew.wlfmovie.providers.Provider
import com.mew.wlfmovie.utils.AppLanguageManager
import com.mew.wlfmovie.utils.ThemeManager
import androidx.core.content.ContextCompat
import com.mew.wlfmovie.utils.UserPreferences
import com.mew.wlfmovie.utils.getCurrentFragment
import kotlinx.coroutines.launch

class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private var updateAppDialog: UpdateAppTvDialog? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))

        super.onCreate(savedInstanceState)

        // WLFMOVIE V3: Verificar actualizaciones al arranque.
        // Si hay update requerida, bloquea la app con UpdateRequiredActivity.
        // Hacemos el check ANTES de inflar el layout para evitar crashes
        // por race conditions con la animación del splash.
        lifecycleScope.launch {
            val blocked = com.mew.wlfmovie.utils.UpdateHelper.checkAndBlockIfRequired(this@MainTvActivity)
            if (blocked) {
                finish()
                return@launch
            }
        }

        // WLFMOVIE: Forzar TMDB (es) como provider al arranque.
        // No importa lo que el user tenia guardado — siempre TMDB.
        if (UserPreferences.currentProvider == null) {
            Log.i("WlfMovie-TV", "Provider era null, forzando TMDb (es)")
        }

        // WLFMOVIE: Eliminadas las llamadas a AnimeOnlineNinjaProvider.init(),
        // Cine24hProvider.init() y FilmyOnlineCcProvider.init().
        // Esos providers NO estan en la lista de providers que usa TmdbProvider
        // para buscar en paralelo al reproducir (ver Provider.providers en
        // Provider.kt). Eran residuos del codigo original StreamFlix.

        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        binding.ivSplashOverlay.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(400)
            .withEndAction {
                // WLFMOVIE: Proteger contra NPE si la activity fue destruida
                // (ej: update check bloqueó la app mientras la animación corría).
                _binding?.ivSplashOverlay?.visibility = View.GONE
            }

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        val navController = navHostFragment.navController

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" ||
            (BuildConfig.APP_LAYOUT != "tv" &&
                !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        ) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            // WLFMOVIE: Siempre navegar al home — currentProvider siempre
            // devuelve TMDb (es), asi que esto siempre se ejecuta.
            UserPreferences.currentProvider?.let {
                navController.navigate(R.id.home)
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.navMain.headerView?.apply {
                val header = ContentHeaderMenuMainTvBinding.bind(this)

                Glide.with(context)
                    .load(R.drawable.ic_wlfmovie_logo)
                    .error(R.drawable.ic_provider_default_logo)
                    .into(header.ivNavigationHeaderIcon)
                header.tvNavigationHeaderTitle.text = "WlfMovie"
                header.tvNavigationHeaderSubtitle.text = getString(R.string.main_menu_settings)
                // WLFMOVIE: Colores fijos WlfMovie (no dependen del tema).
                header.tvNavigationHeaderTitle.setTextColor(0xFFFFFFFF.toInt())
                header.tvNavigationHeaderSubtitle.setTextColor(0xFFB8B8D1.toInt())

                setOnOpenListener {
                    header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                    header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                }
                setOnCloseListener {
                    header.tvNavigationHeaderTitle.visibility = View.GONE
                    header.tvNavigationHeaderSubtitle.visibility = View.GONE
                }

                setOnClickListener {
                    // WLFMOVIE: ir al home en vez de providers/settings
                    if (!navController.popBackStack(R.id.home, false)) {
                        navController.navigate(R.id.home)
                    }
                }
            }

            // WLFMOVIE: misma logica que mobile — mostrar nav lateral en
            // destinos top-level. R.id.lists se anadira en la sub-fase 9.7.
            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.lists -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> binding.navMain.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> showUpdateDialog(state)
                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        dismissUpdateDialog()
                    }
                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        updateAppDialog?.isLoading = false
                        Toast.makeText(
                            this@MainTvActivity,
                            state.error.message ?: "Update failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (navController.currentDestination?.id) {
                    R.id.home -> if (binding.navMain.hasFocus()) finish() else binding.navMain.requestFocus()
                    R.id.search, R.id.movies, R.id.tv_shows, R.id.lists -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkUpdate()
    }

    override fun onDestroy() {
        dismissUpdateDialog()
        _binding = null
        super.onDestroy()
    }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return

        dismissUpdateDialog()
        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases).also { dialog ->
            dialog.setOnUpdateClickListener {
                if (!dialog.isLoading) {
                    viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                }
            }
            dialog.show()
        }
    }

    private fun dismissUpdateDialog() {
        updateAppDialog?.takeIf { it.isShowing }?.dismiss()
        updateAppDialog = null
    }

    private fun applyThemeNavigationChrome() {
        // WLFMOVIE: Fondo morado fijo para el nav lateral (no depende del tema).
        val navBg = ContextCompat.getDrawable(this, R.drawable.wlf_bg_details_fragment)
        window.statusBarColor = 0x00000000
        window.navigationBarColor = 0x00000000
        binding.navMain.background = navBg
        binding.navMain.headerView?.let { headerView ->
            headerView.background = navBg
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(0xFFFFFFFF.toInt())
            header.tvNavigationHeaderSubtitle.setTextColor(0xFFB8B8D1.toInt())
        }
    }

    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (provider is IptvProvider)
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }
}
