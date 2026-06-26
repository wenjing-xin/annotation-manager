import { definePlugin } from '@halo-dev/ui-shared'
import { defineAsyncComponent, markRaw } from 'vue'
import { VLoading } from '@halo-dev/components'
import IconListSettingsLine from '~icons/ri/list-settings-line'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/metadata-manager',
        name: 'MetadataManager',
        component: defineAsyncComponent({
          loader: () => import(/* webpackChunkName: "MetadataManager" */ './views/HomeView.vue'),
          loadingComponent: VLoading,
        }),
        meta: {
          title: '元数据字段管家',
          searchable: true,
          permissions: ['plugin:annotation-manager:metadata:view'],
          menu: {
            name: '元数据字段管家',
            group: 'tool',
            icon: markRaw(IconListSettingsLine),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
