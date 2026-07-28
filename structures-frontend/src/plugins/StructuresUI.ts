import type { NavigationGuardNext, RouteLocationNormalized, Router } from 'vue-router'
import type { App, Plugin } from 'vue'
import {StructuresStates} from '@/states/index'

export function createStructuresUI(): Plugin {
    return {
        install(_: App, options: {router: Router}) {
            options.router.beforeEach(async (to: RouteLocationNormalized, _: RouteLocationNormalized, next: NavigationGuardNext) => {

                const { authenticationRequired } = to.meta
                if (authenticationRequired === undefined || authenticationRequired) {
                    const userState = StructuresStates.getUserState()
                    if (!userState.isAuthenticated()
                        && !(await userState.restoreSession())) {
                        next({ path: '/login', query: { referer: to.fullPath } })
                        return
                    }
                }
                next()
            })

            StructuresStates.getApplicationState().initialize(options.router)
        }
    }
}
