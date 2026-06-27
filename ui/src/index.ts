import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import IconListSettingsLine from '~icons/ri/list-settings-line'
import HomeView from './views/HomeView.vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'ToolsRoot',
      route: {
        path: '/metadata-manager',
        name: 'MetadataManager',
        component: HomeView,
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
