<template>
  <div class="prose mb-10">
    <h1 class="mb-1">Process Import</h1>
    <h2 v-if="originalFilename != null" class="mt-0">{{ originalFilename }}</h2>
    <h2 v-else class="mt-0 invisible">loading...</h2>
  </div>
  <div v-if="msg !== '' && msg != null">
    <div class="bg-red-500 p-4 rounded">
      <p class="font-bold text-white">{{ msg }}</p>
    </div>
  </div>


  <div id="importLog" class="w-full my-10 mx-auto bg-white shadow rounded-lg p-4">
    <h2 class="text-lg font-semibold text-gray-700 mb-2">Logs</h2>
    <hr class="mb-4 border-t border-gray-200">
    <div class="h-32 overflow-auto">
      <ul class="space-y-2">
        <li v-for="(item, index) in workerLog" :key="`logitem-${index}`"
            class="border-b border-gray-200 last:border-b-0">
          <p class="text-sm">{{ item.timestamp }} - {{ item.msg }}</p>
        </li>
      </ul>
    </div>
  </div>

  <Loader v-if="originalFilename == null"/>


  <div>
    <ul class="space-y-4">
      <li v-for="(item, index) in itemsForUser" :key="`item-${index}`" class="bg-white shadow rounded-md p-4">
        <div class="mb-4">
          <label class="block text-gray-700 font-bold mb-2">Name:</label>
          <div class="flex items-center">
            <input v-model="item.properties.name" :placeholder="originalItems[index].properties.name"
                   class="border border-gray-300 rounded-md px-3 py-2 w-full"/>
            <button class="ml-2 bg-gray-200 hover:bg-gray-300 text-gray-700 font-bold py-2 px-4 rounded"
                    @click="resetNestedField(index, 'properties', 'name')">Reset
            </button>
          </div>
        </div>
        <div class="mb-4">
          <label class="block text-gray-700 font-bold mb-2">Description:</label>
          <div class="flex items-center">
            <input v-model="item.properties.description" :placeholder="originalItems[index].properties.description"
                   class="border border-gray-300 rounded-md px-3 py-2 w-full"/>
            <button class="ml-2 bg-gray-200 hover:bg-gray-300 text-gray-700 font-bold py-2 px-4 rounded"
                    @click="resetNestedField(index, 'properties', 'description')">Reset
            </button>
          </div>
        </div>
        <div>
          <label class="block text-gray-700 font-bold mb-2">Created:</label>
          <div class="flex items-center">
            <flat-pickr :config="flatpickrConfig" :value="item.properties.created"
                        class="border border-gray-300 rounded-md px-3 py-2 w-full"
                        @on-change="updateDate(index, $event)"></flat-pickr>
            <button class="ml-2 bg-gray-200 hover:bg-gray-300 text-gray-700 font-bold py-2 px-4 rounded"
                    @click="resetNestedField(index, 'properties', 'created')">Reset
            </button>
          </div>
          <div>
            <label class="block text-gray-700 font-bold mb-2">Tags:</label>
            <div v-for="(tag, tagIndex) in item.properties.tags" :key="`tag-${tagIndex}`" class="mb-2">
              <div class="flex items-center">
                <input v-model="item.properties.tags[tagIndex]" :placeholder="getTagPlaceholder(index, tag)"
                       class="border rounded-md px-3 py-2 w-full bg-white"/>
                <button class="ml-2 bg-red-500 hover:bg-red-600 text-white font-bold py-2 px-4 rounded"
                        @click="removeTag(index, tagIndex)">Remove
                </button>
              </div>
            </div>
          </div>
          <div class="flex items-center mt-2">
            <button :class="{ 'opacity-50 cursor-not-allowed': isLastTagEmpty(index) }"
                    :disabled="isLastTagEmpty(index)"
                    class="bg-blue-500 hover:bg-blue-600 text-white font-bold py-2 px-4 rounded"
                    @click="addTag(index)">Add Tag
            </button>
            <button class="ml-2 bg-gray-200 hover:bg-gray-300 text-gray-700 font-bold py-2 px-4 rounded"
                    @click="resetTags(index)">Reset Tags
            </button>
          </div>
        </div>
      </li>
    </ul>
  </div>

  <div v-if="itemsForUser.length > 0">
    <button :disabled="lockButtons"
            class="m-2 bg-green-500 hover:bg-green-600 disabled:bg-green-300 text-white font-bold py-2 px-4 rounded"
            @click="saveChanges">Save
    </button>
    <button :disabled="lockButtons"
            class="m-2 bg-blue-500 hover:bg-blue-600 disabled:bg-blue-300 text-white font-bold py-2 px-4 rounded"
            @click="performImport">Import
    </button>
  </div>


  <div class="hidden">
    <!-- Load the queue to populate it. -->
    <Importqueue/>
  </div>
</template>

<script>
import {mapState} from "vuex";
import {authMixin} from "@/assets/js/authMixin.js";
import axios from "axios";
import {capitalizeFirstLetter} from "@/assets/js/string.js";
import Importqueue from "@/components/import/parts/importqueue.vue";
import {GeoFeatureTypeStrings} from "@/assets/js/types/geofeature-strings";
import {GeoPoint, GeoLineString, GeoPolygon} from "@/assets/js/types/geofeature-types";
import {getCookie} from "@/assets/js/auth.js";
import flatPickr from 'vue-flatpickr-component';
import 'flatpickr/dist/flatpickr.css';
import Loader from "@/components/parts/Loader.vue";

// TODO: for each feature, query the DB and check if there is a duplicate. For points that's duplicate coords, for linestrings and polygons that's duplicate points
// TODO: redo the entire log feature to include local timestamps

export default {
  computed: {
    ...mapState(["userInfo"]),
  },
  components: {Loader, Importqueue, flatPickr},
  data() {
    return {
      msg: "",
      currentId: null,
      originalFilename: null,
      itemsForUser: [],
      originalItems: [],
      workerLog: [],
      lockButtons: false,
      flatpickrConfig: {
        enableTime: true,
        time_24hr: true,
        dateFormat: 'Y-m-d H:i',
      },
    }
  },
  mixins: [authMixin],
  props: ['id'],
  methods: {
    handleError(responseMsg) {
      console.log(responseMsg)
      this.msg = capitalizeFirstLetter(responseMsg).trim(".") + "."
    },
    parseGeoJson(item) {
      switch (item.geometry.type) {
        case GeoFeatureTypeStrings.Point:
          return new GeoPoint(item);
        case GeoFeatureTypeStrings.LineString:
          return new GeoLineString(item);
        case GeoFeatureTypeStrings.Polygon:
          return new GeoPolygon(item);
        default:
          throw new Error(`Invalid feature type: ${item.type}`);
      }
    },
    resetField(index, fieldName) {
      this.itemsForUser[index][fieldName] = this.originalItems[index][fieldName];
    },
    resetNestedField(index, nestedField, fieldName) {
      this.itemsForUser[index][nestedField][fieldName] = this.originalItems[index][nestedField][fieldName];
    },
    addTag(index) {
      if (!this.isLastTagEmpty(index)) {
        this.itemsForUser[index].properties.tags.push('');
      }
    },
    getTagPlaceholder(index, tag) {
      const originalTagIndex = this.originalItems[index].properties.tags.indexOf(tag);
      return originalTagIndex !== -1 ? this.originalItems[index].properties.tags[originalTagIndex] : '';
    },
    isLastTagEmpty(index) {
      const tags = this.itemsForUser[index].properties.tags;
      return tags.length > 0 && tags[tags.length - 1].trim().length === 0;
    },
    resetTags(index) {
      this.itemsForUser[index].properties.tags = [...this.originalItems[index].properties.tags];
    },
    removeTag(index, tagIndex) {
      this.itemsForUser[index].properties.tags.splice(tagIndex, 1);
    },
    updateDate(index, selectedDates) {
      this.itemsForUser[index].properties.created = selectedDates[0];
    },
    saveChanges() {
      this.lockButtons = true
      const csrftoken = getCookie('csrftoken')
      axios.put('/api/data/item/import/update/' + this.id, this.itemsForUser, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      }).then(response => {
        if (response.data.success) {
          window.alert(response.data.msg);
          // Refresh data from server to reflect persisted values (e.g., regenerated tags)
          axios.get('/api/data/item/import/get/' + this.id).then(res => {
            if (res.data.success) {
              this.itemsForUser = []
              res.data.geofeatures.forEach((item) => {
                this.itemsForUser.push(this.parseGeoJson(item))
              })
              this.originalItems = JSON.parse(JSON.stringify(this.itemsForUser))
            }
          })
        } else {
          this.msg = 'Error saving changes: ' + response.data.msg;
          window.alert(this.msg);
        }
        this.lockButtons = false
      }).catch(error => {
        this.msg = 'Error saving changes: ' + error.message;
        window.alert(this.msg);
      });
    },
    async performImport() {
      this.lockButtons = true
      const csrftoken = getCookie('csrftoken')

      // Save changes first.
      await axios.put('/api/data/item/import/update/' + this.id, this.itemsForUser, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      })

      axios.post('/api/data/item/import/perform/' + this.id, [], {
        headers: {
          'X-CSRFToken': csrftoken
        }
      }).then(response => {
        if (response.data.success) {
          this.$store.dispatch('refreshImportQueue')
          window.alert(response.data.msg);
        } else {
          this.msg = 'Error performing import: ' + response.data.msg;
          window.alert(this.msg);
        }
        this.lockButtons = false
      }).catch(error => {
        this.msg = 'Error performing import: ' + error.message;
        window.alert(this.msg);
        this.lockButtons = false
      });
    },
  },
  beforeRouteEnter(to, from, next) {
    const now = new Date().toISOString()
    let ready = false
    next(async vm => {
      if (vm.currentId !== vm.id) {
        vm.msg = ""
        vm.currentId = null
        vm.originalFilename = null
        vm.itemsForUser = []
        vm.originalItems = []
        vm.workerLog = []
        vm.lockButtons = false
        while (!ready) {
          try {
            const response = await axios.get('/api/data/item/import/get/' + vm.id)
            if (!response.data.success) {
              vm.handleError(response.data.msg)
            } else {
              vm.currentId = vm.id
              if (Object.keys(response.data).length > 0) {
                vm.originalFilename = response.data.original_filename
                response.data.geofeatures.forEach((item) => {
                  vm.itemsForUser.push(vm.parseGeoJson(item))
                })
                vm.originalItems = JSON.parse(JSON.stringify(vm.itemsForUser))
              }
              if (!response.data.processing) {
                vm.workerLog = vm.workerLog.concat(response.data.log)
                if (response.data.msg != null && response.data.msg.length > 0) {
                  vm.workerLog.push({timestamp: now, msg: response.data.msg})
                }
                ready = true
              } else {
                vm.workerLog = [{timestamp: now, msg: "uploaded data still processing"}]
                await new Promise(r => setTimeout(r, 1000));
              }
            }
          } catch (error) {
            vm.handleError(error.message)
          }
        }
      }
    })
  }
  ,
}

</script>

<style scoped>

</style>