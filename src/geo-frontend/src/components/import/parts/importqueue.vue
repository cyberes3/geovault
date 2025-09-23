<template>
  <table class="mt-6 w-full border-collapse">
    <thead>
    <tr class="bg-gray-100">
      <th class="px-4 py-2 text-left w-[50%]">File Name</th>
      <th class="px-4 py-2 text-center">Features</th>
      <th class="px-4 py-2 w-[10%]"></th>
    </tr>
    </thead>
    <tbody>
    <tr v-for="(item, index) in importQueue" :key="`item-${index}`" class="border-t">
      <td class="px-4 py-2 w-[50%]">
        <a :href="`/#/import/process/${item.id}`" class="text-blue-500 hover:text-blue-700">{{
            item.original_filename
          }}</a>
        <span v-if="item.imported" class="ml-2 px-2 py-1 text-xs bg-green-100 text-green-800 rounded-full">
          Imported
        </span>
      </td>
      <td class="px-4 py-2 text-center">
        {{ item.processing === true || (item.processing === false && item.feature_count === -1) ? "processing" : item.feature_count }}
      </td>
      <td class="px-4 py-2 w-[10%]">
        <button class="px-4 py-2 bg-red-500 text-white rounded hover:bg-red-600" @click="deleteItem(item, index)">
          Delete
        </button>
      </td>
    </tr>
    <tr v-if="isLoading && importQueue.length === 0" class="animate-pulse border-t">
      <td class="px-4 py-2 text-left w-[50%]">
        <div class="w-32 h-8 bg-gray-200 rounded-s"></div>
      </td>
      <td class="px-4 py-2 text-center">
        <div class="w-32 h-8 bg-gray-200 rounded-s mx-auto"></div>
      </td>
      <td class="px-4 py-2 invisible w-[10%]">
        <button class="px-4 py-2 bg-red-500 text-white rounded hover:bg-red-600">
          Delete
        </button>
      </td>
    </tr>
    </tbody>
  </table>
</template>

<script>
import {mapState} from "vuex";
import {authMixin} from "@/assets/js/authMixin.js";
import axios from "axios";
import {IMPORT_QUEUE_LIST_URL} from "@/assets/js/import/url.js";
import {ImportQueueItem} from "@/assets/js/types/import-types";
import {getCookie} from "@/assets/js/auth.js";

export default {
  computed: {
    ...mapState(["userInfo", "importQueue"]),
  },
  components: {},
  mixins: [authMixin],
  data() {
    return {
      isLoading: true,
    }
  },
  methods: {
    subscribeToRefreshMutation() {
      this.$store.subscribe((mutation, state) => {
        if (mutation.type === 'triggerImportQueueRefresh') {
          this.refreshData();
        }
      });
    },
    async refreshData() {
      console.log("IMPORT QUEUE: refreshing")
      await this.fetchQueueList()
    },
    async fetchQueueList() {
      this.isLoading = true
      const response = await axios.get(IMPORT_QUEUE_LIST_URL)
      const ourImportQueue = response.data.data.map((item) => new ImportQueueItem(item))
      this.$store.commit('setImportQueue', ourImportQueue)
      this.isLoading = false
    },
    async deleteItem(item, index) {
      if (window.confirm(`Delete "${item.original_filename}" (#${item.id})`)) {
        try {
          this.importQueue.splice(index, 1);
          const response = await axios.delete('/api/data/item/import/delete/' + item.id, {
            headers: {
              'X-CSRFToken': getCookie('csrftoken')
            }
          });
          if (!response.data.success) {
            throw new Error("server reported failure");
          }
          await this.refreshData(); // Refresh the data after deleting an item
        } catch (error) {
          alert(`Failed to delete ${item.id}: ${error.message}`);
          this.importQueue.splice(index, 0, item);
        }
      }
    },
  },
  async created() {
    await this.fetchQueueList()
    this.subscribeToRefreshMutation()
  },
}
</script>

<style scoped>

</style>
