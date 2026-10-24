/* Copyright (c) 2026 YiZheChat contributors. SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial. */
package io.element.android.features.discovery.impl

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import com.bumble.appyx.navmodel.backstack.operation.newRoot
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.discovery.api.DiscoveryAccess
import io.element.android.features.discovery.api.DiscoveryError
import io.element.android.features.discovery.impl.screens.academy.DiscoveryAcademyPresenter
import io.element.android.features.discovery.impl.screens.academy.DiscoveryAcademyView
import io.element.android.features.discovery.impl.screens.home.DiscoveryHomePresenter
import io.element.android.features.discovery.impl.screens.home.DiscoveryHomeView
import io.element.android.features.discovery.impl.screens.home.DiscoveryBannerTarget
import io.element.android.features.discovery.impl.screens.home.bannerTarget
import io.element.android.features.discovery.impl.screens.courses.DiscoveryCoursesPresenter
import io.element.android.features.discovery.impl.screens.courses.DiscoveryCoursesView
import io.element.android.features.discovery.impl.screens.courses.DiscoveryCourseDetailPresenter
import io.element.android.features.discovery.impl.screens.courses.DiscoveryCourseDetailView
import io.element.android.features.discovery.impl.screens.courses.openCourseLocation
import io.element.android.features.discovery.impl.screens.member.DiscoveryAddressNodeFactory
import io.element.android.features.discovery.impl.screens.member.DiscoveryPersonNodeFactory
import io.element.android.features.discovery.impl.screens.mall.DiscoveryMallNodeFactory
import io.element.android.features.discovery.impl.screens.services.DiscoveryServicesNodeFactory
import io.element.android.features.discovery.impl.screens.services.feedback.FeedbackTarget
import io.element.android.features.discovery.impl.screens.member.PersonEditKind
import io.element.android.features.discovery.impl.screens.member.PersonEditTarget
import io.element.android.features.discovery.impl.screens.courses.enrollment.DiscoveryEnrollmentNodeFactory
import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticlesPresenter
import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticlesView
import io.element.android.features.discovery.impl.screens.articles.DiscoveryArticleNode
import com.bumble.appyx.core.node.LocalNodeTargetVisibility
import io.element.android.features.discovery.impl.screens.articles.ArticleCollection
import io.element.android.features.discovery.impl.screens.articles.ArticleCollectionKind
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalKind
import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalPresenter
import io.element.android.features.discovery.impl.screens.articles.DiscoveryPersonalView
import io.element.android.features.discovery.impl.screens.articles.DiscoveryCommentsPresenter
import io.element.android.features.discovery.impl.screens.articles.DiscoveryReadingPresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

@ContributesNode(SessionScope::class)
@AssistedInject
class DiscoveryNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val access: DiscoveryAccess,
    private val homePresenter: DiscoveryHomePresenter,
    private val coursesPresenter: DiscoveryCoursesPresenter,
    private val courseDetailPresenter: DiscoveryCourseDetailPresenter,
    private val academyPresenter: DiscoveryAcademyPresenter,
    private val articlesPresenter: DiscoveryArticlesPresenter,
    private val personalPresenter: DiscoveryPersonalPresenter,
    private val readingPresenter: DiscoveryReadingPresenter,
    private val commentsPresenter: DiscoveryCommentsPresenter,
    private val addressNodeFactory: DiscoveryAddressNodeFactory,
    private val enrollmentNodeFactory: DiscoveryEnrollmentNodeFactory,
    private val personNodeFactory: DiscoveryPersonNodeFactory,
    private val mallNodeFactory: DiscoveryMallNodeFactory,
    private val servicesNodeFactory: DiscoveryServicesNodeFactory,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) : BaseFlowNode<DiscoveryNode.NavTarget>(
    BackStack(initialElement = NavTarget.Home, savedStateMap = buildContext.savedStateMap), buildContext, plugins,
) {
    private val contentResumeRevision = MutableStateFlow(0L)
    private val contentResumed = MutableStateFlow(false)
    private val returnedEnrollmentPerson = MutableStateFlow<Long?>(null)

    sealed interface NavTarget : Parcelable {
        @Parcelize data object Home : NavTarget
        @Parcelize data object Courses : NavTarget
        @Parcelize data object Services : NavTarget
        @Parcelize data class ServiceTeachers(val courseId: Long, val sessionId: Long, val enrollmentId: Long) : NavTarget
        @Parcelize data class CourseFeedback(val courseId: Long, val sessionId: Long, val enrollmentId: Long) : NavTarget
        @Parcelize data object Profile : NavTarget
        @Parcelize data object Addresses : NavTarget
        @Parcelize data object Shop : NavTarget
        @Parcelize data object MallOrders : NavTarget
        @Parcelize data object MallResult : NavTarget
        @Parcelize data class MallProduct(val productId: Long) : NavTarget
        @Parcelize data class MallCheckout(val skuId: Long, val quantity: Int, val addressId: Long? = null) : NavTarget
        @Parcelize data class MallAddressSelection(val skuId: Long, val quantity: Int) : NavTarget
        @Parcelize data class MallOrder(val orderId: Long) : NavTarget
        @Parcelize data class MallAfterSale(val orderId: Long, val afterSaleId: Long? = null) : NavTarget
        @Parcelize data class People(val childrenOnly: Boolean) : NavTarget
        @Parcelize data class EditPerson(val kind: PersonEditKind, val userId: Long?, val forEnrollment: Boolean = false) : NavTarget
        @Parcelize data class Enrollment(val courseId: Long, val sessionId: Long) : NavTarget
        @Parcelize data class CourseDetail(val courseId: Long, val sessionId: Long? = null) : NavTarget
        @Parcelize data class Academy(val id: Long) : NavTarget
        @Parcelize data class Articles(val search: Boolean) : NavTarget
        @Parcelize data class Article(val id: Long, val comments: Boolean = false) : NavTarget
        @Parcelize data class Tag(val id: Long) : NavTarget
        @Parcelize data class Author(val id: Long) : NavTarget
        @Parcelize data class Personal(val kind: DiscoveryPersonalKind) : NavTarget
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node = if (navTarget is NavTarget.Article) {
        DiscoveryArticleNode(buildContext, navTarget.id, articlesPresenter, readingPresenter, commentsPresenter, contentResumed, contentResumeRevision, navTarget.comments,
            onBack = { backstack.pop() }, onAuthor = { backstack.push(NavTarget.Author(it)) }, onTag = { backstack.push(NavTarget.Tag(it)) })
    } else if (navTarget == NavTarget.Profile) {
        personNodeFactory.profile(buildContext, contentResumed, ::selectRoot,
            onPersonal = { backstack.push(NavTarget.Personal(it)) }, onSearch = { backstack.push(NavTarget.Articles(true)) },
            onAddresses = { backstack.push(NavTarget.Addresses) }, onPeople = { backstack.push(NavTarget.People(false)) },
            onChildren = { backstack.push(NavTarget.People(true)) }, onMallOrders = { backstack.push(NavTarget.MallOrders) },
            onEdit = { backstack.push(NavTarget.EditPerson(it.kind, it.userId)) })
    } else if (navTarget == NavTarget.Services) {
        servicesNodeFactory.root(buildContext, contentResumed, ::selectRoot,
            onTeachers = { backstack.push(NavTarget.ServiceTeachers(it.courseId, it.sessionId, it.enrollmentId)) },
            onFeedback = { backstack.push(NavTarget.CourseFeedback(it.courseId, it.sessionId, it.enrollmentId)) },
            onShop = { backstack.push(NavTarget.Shop) }, onOrders = { backstack.push(NavTarget.MallOrders) })
    } else if (navTarget is NavTarget.CourseFeedback) {
        servicesNodeFactory.feedback(buildContext, contentResumed, FeedbackTarget(navTarget.courseId, navTarget.sessionId, navTarget.enrollmentId),
            onBack = { contentResumeRevision.value++; backstack.pop() }, onSaved = { contentResumeRevision.value++ })
    } else if (navTarget is NavTarget.ServiceTeachers) {
        servicesNodeFactory.teachers(buildContext, contentResumed, FeedbackTarget(navTarget.courseId, navTarget.sessionId, navTarget.enrollmentId),
            onBack = { backstack.pop() })
    } else if (navTarget == NavTarget.Shop) {
        mallNodeFactory.catalog(buildContext, contentResumed, onBack = { backstack.pop() },
            onProduct = { backstack.push(NavTarget.MallProduct(it)) }, onOrders = { backstack.push(NavTarget.MallOrders) },
            onResult = { backstack.push(NavTarget.MallResult) })
    } else if (navTarget is NavTarget.MallProduct) {
        mallNodeFactory.product(buildContext, contentResumed, navTarget.productId, onBack = { backstack.pop() },
            onCheckout = { sku, count -> backstack.push(NavTarget.MallCheckout(sku, count)) })
    } else if (navTarget is NavTarget.MallCheckout) {
        mallNodeFactory.checkout(buildContext, contentResumed, navTarget.skuId, navTarget.quantity, navTarget.addressId,
            onBack = { backstack.pop() }, onAddress = { backstack.push(NavTarget.MallAddressSelection(navTarget.skuId, navTarget.quantity)) },
            onResult = { backstack.push(NavTarget.MallResult) })
    } else if (navTarget is NavTarget.MallAddressSelection) {
        addressNodeFactory.choose(buildContext, contentResumed, onBack = { backstack.pop() }, onSelected = { address ->
            backstack.pop()
            backstack.replace(NavTarget.MallCheckout(navTarget.skuId, navTarget.quantity, address))
        })
    } else if (navTarget == NavTarget.MallOrders) {
        mallNodeFactory.orders(buildContext, contentResumed, onBack = { backstack.newRoot(NavTarget.Profile) },
            onOrder = { backstack.push(NavTarget.MallOrder(it)) }, onResult = { backstack.push(NavTarget.MallResult) })
    } else if (navTarget is NavTarget.MallOrder) {
        mallNodeFactory.order(buildContext, contentResumed, navTarget.orderId, onBack = { backstack.pop() },
            onAfterSale = { backstack.push(NavTarget.MallAfterSale(navTarget.orderId, it)) }, onResult = { backstack.push(NavTarget.MallResult) })
    } else if (navTarget is NavTarget.MallAfterSale) {
        mallNodeFactory.afterSale(buildContext, contentResumed, navTarget.orderId, navTarget.afterSaleId,
            onBack = { backstack.pop() }, onResult = { backstack.push(NavTarget.MallResult) })
    } else if (navTarget == NavTarget.MallResult) {
        mallNodeFactory.result(buildContext, contentResumed, onBack = { backstack.pop() }, onOrder = { id ->
            backstack.newRoot(NavTarget.MallOrders)
            backstack.push(NavTarget.MallOrder(id))
        })
    } else if (navTarget == NavTarget.Addresses) {
        addressNodeFactory.create(buildContext, contentResumed, onBack = { backstack.pop() })
    } else if (navTarget is NavTarget.People) {
        personNodeFactory.people(buildContext, navTarget.childrenOnly, contentResumed, contentResumeRevision,
            onBack = { backstack.pop() }, onEdit = { backstack.push(NavTarget.EditPerson(it.kind, it.userId)) })
    } else if (navTarget is NavTarget.EditPerson) {
        personNodeFactory.editor(buildContext, PersonEditTarget(navTarget.kind, navTarget.userId), contentResumed,
            onBack = { contentResumeRevision.value++; backstack.pop() }, onSaved = { result, use ->
                if (use && navTarget.forEnrollment) returnedEnrollmentPerson.value = result.person.id
                contentResumeRevision.value++
                backstack.pop()
            }, allowUse = navTarget.forEnrollment)
    } else if (navTarget is NavTarget.Enrollment) {
        enrollmentNodeFactory.create(buildContext, navTarget.courseId, navTarget.sessionId, contentResumed, contentResumeRevision,
            onBack = { returnedEnrollmentPerson.value = null; backstack.pop() }, onCourses = { returnedEnrollmentPerson.value = null; backstack.newRoot(NavTarget.Courses) },
            onAddChild = { backstack.push(NavTarget.EditPerson(PersonEditKind.NEW_CHILD, null, true)) },
            onEditTarget = { backstack.push(NavTarget.EditPerson(it.kind, it.userId, true)) },
            returnedPerson = returnedEnrollmentPerson, onPersonConsumed = { returnedEnrollmentPerson.value = null })
    } else node(buildContext) { modifier ->
        val context = LocalContext.current
        val visible = LocalNodeTargetVisibility.current
        val resumeRevision by contentResumeRevision.collectAsState()
        val resumed by contentResumed.collectAsState()
        val readingRevision by readingPresenter.revision.collectAsState()
        when (navTarget) {
            NavTarget.Home -> DiscoveryRootNavigation(DiscoveryRootTab.HOME, ::selectRoot, modifier) {
                DiscoveryHomeView(homePresenter.present(visible && resumed, resumeRevision),
                    onNews = { backstack.push(NavTarget.Articles(false)) },
                    onSearch = { backstack.push(NavTarget.Articles(true)) },
                    onArticle = { backstack.push(NavTarget.Article(it)) }, onAcademy = { backstack.push(NavTarget.Academy(it)) },
                    onCourses = { backstack.newRoot(NavTarget.Courses) },
                    onSession = { courseId, sessionId -> backstack.push(NavTarget.CourseDetail(courseId, sessionId)) },
                    onBanner = { card ->
                        when (val target = card.bannerTarget()) {
                            DiscoveryBannerTarget.Home -> backstack.newRoot(NavTarget.Home)
                            DiscoveryBannerTarget.Courses -> backstack.newRoot(NavTarget.Courses)
                            DiscoveryBannerTarget.Services -> backstack.newRoot(NavTarget.Services)
                            DiscoveryBannerTarget.Profile -> backstack.newRoot(NavTarget.Profile)
                            is DiscoveryBannerTarget.Course -> backstack.push(NavTarget.CourseDetail(target.id, target.sessionId))
                            is DiscoveryBannerTarget.Article -> backstack.push(NavTarget.Article(target.id))
                            is DiscoveryBannerTarget.Articles -> backstack.push(NavTarget.Articles(target.search))
                            is DiscoveryBannerTarget.Academy -> backstack.push(NavTarget.Academy(target.id))
                            is DiscoveryBannerTarget.Personal -> backstack.push(NavTarget.Personal(target.kind))
                            null -> Unit
                        }
                    })
            }
            NavTarget.Courses -> DiscoveryRootNavigation(DiscoveryRootTab.COURSES, ::selectRoot, modifier) {
                DiscoveryCoursesView(coursesPresenter.present(visible && resumed, resumeRevision),
                    onSession = { courseId, sessionId -> backstack.push(NavTarget.CourseDetail(courseId, sessionId)) },
                    onLocation = { openCourseLocation(context, it) })
            }
            NavTarget.Services, NavTarget.Profile -> error("Services and profile use their own node lifecycle")
            is NavTarget.CourseDetail -> DiscoveryCourseDetailView(
                courseDetailPresenter.present(navTarget.courseId, navTarget.sessionId, visible && resumed, resumeRevision),
                onBack = { backstack.pop() },
                onEnroll = { courseId, sessionId -> backstack.push(NavTarget.Enrollment(courseId, sessionId)) },
                modifier = modifier)
            is NavTarget.Academy -> DiscoveryAcademyView(academyPresenter.present(navTarget.id, visible && resumed, resumeRevision),
                onBack = { backstack.pop() }, onNext = { backstack.replace(NavTarget.Academy(it)) },
                onHome = { backstack.newRoot(NavTarget.Home) }, modifier = modifier)
            is NavTarget.Articles -> DiscoveryArticlesView(articlesPresenter.present(navTarget.search, active = visible && resumed,
                resumeRevision = resumeRevision, foreground = resumed),
                onBack = { backstack.pop() }, onArticle = { backstack.push(NavTarget.Article(it)) }, modifier = modifier,
                onPersonal = { backstack.push(NavTarget.Personal(it)) })
            is NavTarget.Personal -> DiscoveryPersonalView(
                personalPresenter.present(navTarget.kind, visible && resumed,
                    resumeRevision + if (navTarget.kind == DiscoveryPersonalKind.HISTORY) readingRevision else 0L),
                onBack = { backstack.pop() }, onArticle = { backstack.push(NavTarget.Article(it)) },
                onAuthor = { backstack.push(NavTarget.Author(it)) }, modifier = modifier,
                onReply = { backstack.push(NavTarget.Article(it, comments = true)) })
            is NavTarget.Article -> error("Article uses its own node lifecycle")
            NavTarget.Addresses -> error("Addresses use their own node lifecycle")
            is NavTarget.Enrollment -> error("Enrollment uses its own node lifecycle")
            is NavTarget.People, is NavTarget.EditPerson -> error("People use their own node lifecycle")
            is NavTarget.ServiceTeachers -> error("Teachers use their own node lifecycle")
            is NavTarget.CourseFeedback -> error("Feedback uses its own node lifecycle")
            NavTarget.Shop, NavTarget.MallOrders, NavTarget.MallResult, is NavTarget.MallProduct, is NavTarget.MallCheckout,
            is NavTarget.MallAddressSelection, is NavTarget.MallOrder, is NavTarget.MallAfterSale -> error("Mall pages use their own node lifecycle")
            is NavTarget.Tag -> DiscoveryArticlesView(articlesPresenter.present(false, ArticleCollection(ArticleCollectionKind.TAG, navTarget.id), visible && resumed, resumeRevision, resumed),
                onBack = { backstack.pop() }, onArticle = { backstack.push(NavTarget.Article(it)) }, modifier = modifier)
            is NavTarget.Author -> DiscoveryArticlesView(articlesPresenter.present(false, ArticleCollection(ArticleCollectionKind.AUTHOR, navTarget.id), visible && resumed, resumeRevision, resumed),
                onBack = { backstack.pop() }, onArticle = { backstack.push(NavTarget.Article(it)) }, modifier = modifier)
        }
    }
    private fun selectRoot(tab: DiscoveryRootTab) {
        backstack.newRoot(when (tab) {
            DiscoveryRootTab.HOME -> NavTarget.Home
            DiscoveryRootTab.COURSES -> NavTarget.Courses
            DiscoveryRootTab.SERVICES -> NavTarget.Services
            DiscoveryRootTab.PROFILE -> NavTarget.Profile
        })
    }

    @Composable
    override fun View(modifier: Modifier) {
        val state by access.state.collectAsState()
        var entryAttempted by remember { mutableStateOf(false) }
        var enteredOnce by remember { mutableStateOf(false) }
        var retry by remember { mutableStateOf(0) }
        LifecycleResumeEffect(Unit) {
            contentResumed.value = true
            contentResumeRevision.value++
            retry++
            onPauseOrDispose { contentResumed.value = false }
        }
        LaunchedEffect(retry) {
            if (retry == 0) return@LaunchedEffect
            entryAttempted = false
            access.enter()
            entryAttempted = true
        }
        LaunchedEffect(state.entered, retry) { if (state.entered) readingPresenter.recover() }
        DisposableEffect(Unit) {
            onDispose { sessionScope.launch { access.leave() } }
        }
        LaunchedEffect(access.configured, state, entryAttempted) {
            if (state.entered) enteredOnce = true
            if (!access.configured || (entryAttempted && !state.entered && !state.busy &&
                (enteredOnce || state.error == DiscoveryError.DENIED || state.error == null))) {
                // A restored route is never an access grant. Return to the existing host tab.
                navigateUp()
            }
        }
        if (state.entered) {
            BackstackView(modifier)
        } else {
            DiscoveryAccessView(
                busy = !entryAttempted || state.busy,
                error = state.error,
                onRetry = { retry++ },
                onExit = ::navigateUp,
                modifier = modifier,
            )
        }
    }
}
