package org.fossasia.openevent.general.event

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.navigation.Navigation.findNavController
import androidx.navigation.Navigator
import androidx.navigation.fragment.FragmentNavigatorExtras
import kotlinx.android.synthetic.main.content_no_internet.view.noInternetCard
import kotlinx.android.synthetic.main.content_no_internet.view.retry
import kotlinx.android.synthetic.main.fragment_events.eventsNestedScrollView
import kotlinx.android.synthetic.main.fragment_events.view.eventsRecycler
import kotlinx.android.synthetic.main.fragment_events.view.homeScreenLL
import kotlinx.android.synthetic.main.fragment_events.view.locationTextView
import kotlinx.android.synthetic.main.fragment_events.view.progressBar
import kotlinx.android.synthetic.main.fragment_events.view.shimmerEvents
import kotlinx.android.synthetic.main.fragment_events.view.swiperefresh
import kotlinx.android.synthetic.main.fragment_events.view.eventsEmptyView
import kotlinx.android.synthetic.main.fragment_events.view.emptyEventsText
import kotlinx.android.synthetic.main.fragment_events.view.eventsNestedScrollView
import kotlinx.android.synthetic.main.item_card_events.view.eventImage
import org.fossasia.openevent.general.R
import org.fossasia.openevent.general.ScrollToTop
import org.fossasia.openevent.general.common.EventClickListener
import org.fossasia.openevent.general.common.EventsDiffCallback
import org.fossasia.openevent.general.common.FavoriteFabClickListener
import org.fossasia.openevent.general.data.Preference
import org.fossasia.openevent.general.search.SAVED_LOCATION
import org.fossasia.openevent.general.utils.extensions.nonNull
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import org.fossasia.openevent.general.utils.Utils.setToolbar
import org.fossasia.openevent.general.utils.extensions.setPostponeSharedElementTransition
import org.fossasia.openevent.general.utils.extensions.setStartPostponedEnterTransition
import org.jetbrains.anko.design.longSnackbar

/**
 * Enum class for different layout types in the adapter.
 * This class can expand as number of layout types grow.
 */
enum class EventLayoutType {
    EVENTS, SIMILAR_EVENTS
}

const val EVENT_DATE_FORMAT: String = "eventDateFormat"
const val BEEN_TO_WELCOME_SCREEN = "beenToWelcomeScreen"

class EventsFragment : Fragment(), ScrollToTop {
    private val eventsViewModel by viewModel<EventsViewModel>()
    private lateinit var rootView: View
    private val preference = Preference()
    private val eventsListAdapter = EventsListAdapter(EventLayoutType.EVENTS, EventsDiffCallback())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setPostponeSharedElementTransition()
        rootView = inflater.inflate(R.layout.fragment_events, container, false)
        setHasOptionsMenu(true)
        if (preference.getString(SAVED_LOCATION).isNullOrEmpty() &&
            !preference.getBoolean(BEEN_TO_WELCOME_SCREEN, false)) {
            preference.putBoolean(BEEN_TO_WELCOME_SCREEN, true)
            findNavController(requireActivity(), R.id.frameContainer).navigate(R.id.welcomeFragment)
        }
        setToolbar(activity, getString(R.string.events), false)

        rootView.progressBar.isIndeterminate = true

        rootView.eventsRecycler.layoutManager =
            GridLayoutManager(activity, resources.getInteger(R.integer.events_column_count))

        rootView.eventsRecycler.adapter = eventsListAdapter
        rootView.eventsRecycler.isNestedScrollingEnabled = false

        eventsViewModel.showShimmerEvents
            .nonNull()
            .observe(viewLifecycleOwner, Observer { shouldShowShimmer ->
                if (shouldShowShimmer) {
                    rootView.shimmerEvents.startShimmer()
                    eventsListAdapter.clear()
                } else {
                    rootView.shimmerEvents.stopShimmer()
                }
                rootView.shimmerEvents.isVisible = shouldShowShimmer
            })

        eventsViewModel.events
            .nonNull()
            .observe(this, Observer { list ->
                eventsListAdapter.submitList(list)
                showEmptyMessage(list.size)
                Timber.d("Fetched events of size %s", eventsListAdapter.itemCount)
            })

        eventsViewModel.progress
            .nonNull()
            .observe(viewLifecycleOwner, Observer {
                rootView.swiperefresh.isRefreshing = it
            })

        eventsViewModel.error
            .nonNull()
            .observe(viewLifecycleOwner, Observer {
                eventsNestedScrollView.longSnackbar(it)
            })

        eventsViewModel.loadLocation()
        rootView.locationTextView.text = eventsViewModel.savedLocation.value

        eventsViewModel.savedLocation
            .nonNull()
            .observe(viewLifecycleOwner, Observer {
                if (eventsViewModel.lastSearch != it) {
                    eventsViewModel.clearEvents()
                }
            })

        eventsViewModel.connection
            .nonNull()
            .observe(viewLifecycleOwner, Observer { isConnected ->
                if (isConnected && eventsViewModel.events.value == null) {
                    eventsViewModel.loadLocationEvents()
                }
                showNoInternetScreen(!isConnected && eventsViewModel.events.value == null)
            })

        rootView.locationTextView.setOnClickListener {
            findNavController(rootView).navigate(EventsFragmentDirections.actionEventsToSearchLocation())
        }

        rootView.retry.setOnClickListener {
            if (eventsViewModel.savedLocation.value != null && eventsViewModel.isConnected()) {
                eventsViewModel.loadLocationEvents()
            }
            showNoInternetScreen(!eventsViewModel.isConnected())
        }

        rootView.swiperefresh.setColorSchemeColors(Color.BLUE)
        rootView.swiperefresh.setOnRefreshListener {
            showNoInternetScreen(!eventsViewModel.isConnected())
            eventsViewModel.clearEvents()
            eventsViewModel.clearLastSearch()
            if (!eventsViewModel.isConnected()) {
                rootView.swiperefresh.isRefreshing = false
            } else {
                eventsViewModel.loadLocationEvents()
            }
        }

        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView.eventsRecycler.viewTreeObserver.addOnGlobalLayoutListener {
            setStartPostponedEnterTransition()
        }

        val eventClickListener: EventClickListener = object : EventClickListener {
            override fun onClick(eventID: Long, itemPosition: Int) {
                var extras: Navigator.Extras? = null
                val itemEventViewHolder = rootView.eventsRecycler.findViewHolderForAdapterPosition(itemPosition)
                itemEventViewHolder?.let {
                    if (itemEventViewHolder is EventViewHolder) {
                        extras = FragmentNavigatorExtras(
                            itemEventViewHolder.itemView.eventImage to "eventDetailImage")
                    }
                }
                extras?.let {
                    findNavController(view).navigate(EventsFragmentDirections.actionEventsToEventsDetail(eventID), it)
                } ?: findNavController(view).navigate(EventsFragmentDirections.actionEventsToEventsDetail(eventID))
            }
        }

        val favFabClickListener: FavoriteFabClickListener = object : FavoriteFabClickListener {
            override fun onClick(event: Event, itemPosition: Int) {
                eventsViewModel.setFavorite(event.id, !event.favorite)
                event.favorite = !event.favorite
                eventsListAdapter.notifyItemChanged(itemPosition)
            }
        }

        val hashTagClickListener: EventHashTagClickListener = object : EventHashTagClickListener {
            override fun onClick(hashTagValue: String) {
                openSearch(hashTagValue)
            }
        }

        eventsListAdapter.apply {
            onEventClick = eventClickListener
            onFavFabClick = favFabClickListener
            onHashtagClick = hashTagClickListener
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        eventsListAdapter.apply {
            onEventClick = null
            onFavFabClick = null
            onHashtagClick = null
        }
    }

    private fun openSearch(hashTag: String) {
            findNavController(rootView).navigate(EventsFragmentDirections.actionEventsToSearchResults(
                query = "",
                location = Preference().getString(SAVED_LOCATION).toString(),
                date = getString(R.string.anytime),
                type = hashTag))
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.events, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.notifications -> {
                findNavController(rootView).navigate(EventsFragmentDirections.actionEventsToNotification())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showNoInternetScreen(show: Boolean) {
        rootView.homeScreenLL.visibility = if (!show) View.VISIBLE else View.GONE
        rootView.noInternetCard.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmptyMessage(itemCount: Int) {
        if (itemCount == 0) {
            rootView.eventsEmptyView.visibility = View.VISIBLE
            if (rootView.locationTextView.text == getString(R.string.choose_your_location)) {
                rootView.emptyEventsText.text = getString(R.string.choose_preferred_location_message)
            } else {
                rootView.emptyEventsText.text = getString(R.string.no_events_message)
            }
        } else {
            rootView.eventsEmptyView.visibility = View.GONE
        }
    }

    override fun onStop() {
        rootView.swiperefresh?.setOnRefreshListener(null)
        super.onStop()
    }

    override fun scrollToTop() = rootView.eventsNestedScrollView.smoothScrollTo(0, 0)
}
