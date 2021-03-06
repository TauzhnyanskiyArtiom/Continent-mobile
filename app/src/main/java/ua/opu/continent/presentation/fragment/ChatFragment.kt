package ua.opu.continent.presentation.fragment

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import ua.opu.continent.App
import ua.opu.continent.R
import ua.opu.continent.databinding.FragmentChatBinding
import ua.opu.continent.presentation.MainViewModel
import ua.opu.continent.presentation.MainViewModelFactory
import ua.opu.continent.presentation.adapter.MessagesAdapter
import ua.opu.continent.presentation.dialog.DeleteMessageDialog
import ua.opu.continent.presentation.dialog.ProgressDialog
import ua.opu.continent.presentation.dto.MessageCreateDto
import ua.opu.continent.useсase.impl.PresenceUseCaseFirebase
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject


class ChatFragment() : Fragment(R.layout.fragment_chat) {

    private lateinit var binding: FragmentChatBinding
    private lateinit var adapter: MessagesAdapter
    private lateinit var dialog: ProgressDialog
    private var senderRoom: String? = null
    private var receiverRoom: String? = null
    private var senderUid: String? = null
    private var receiverUid: String? = null
    private var photoURI: Uri? = null

    @Inject
    lateinit var viewModelFactory: MainViewModelFactory
    private lateinit var viewModel: MainViewModel

    private val contentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null)
            savePhotoFirebase(it)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) {
        if (it)
            savePhotoFirebase(photoURI!!)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (it) {
            photoURI = getFileUri()
            cameraLauncher.launch(photoURI)
        }
        Log.d(ChatFragment::class.simpleName, "Permission result^ $it")
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as AppCompatActivity?)?.setSupportActionBar(binding.toolbar)

        senderUid = FirebaseAuth.getInstance().uid

        initReceiver()

        binding.backImage.setOnClickListener {
            findNavController().popBackStack()
        }

        initMessageAdapter()

        binding.sendBtn.setOnClickListener {
            val messageTxt: String = binding.messageBox.text.toString()
            val messageCreateDto = MessageCreateDto.Builder()
                .message(messageTxt)
                .senderRoom(senderRoom!!)
                .receiverRoom(receiverRoom!!)
                .build()
            viewModel.sendMessage(messageCreateDto)
            binding.messageBox.setText("")
        }

        binding.attachment.setOnClickListener {
            contentLauncher.launch("image/*")
        }
        binding.camera.setOnClickListener {
            grantCameraPermission()
        }
        val handler = Handler()
        binding.messageBox.addTextChangedListener(textWatcher(handler))

        (activity as AppCompatActivity?)?.supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        binding = FragmentChatBinding.inflate(inflater, container, false)
        (requireActivity().applicationContext as App).appComponent.inject(this)

        viewModel = ViewModelProvider(this, viewModelFactory)[MainViewModel::class.java]
        return binding.root
    }


    private fun initMessageAdapter() {
        senderRoom = senderUid + receiverUid
        receiverRoom = receiverUid + senderUid
        adapter = MessagesAdapter(requireContext()) {
            val dialogDelete = DeleteMessageDialog.newInstance(
                it.messageId,
                senderRoom!!, receiverRoom!!
            )
            dialogDelete.show(parentFragmentManager, "delete")
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        adapter.submitList(null)

        viewModel.bindToGetAllMessages(senderRoom!!, adapter)
    }

    private fun initReceiver() {
        val name = arguments?.getString("name")
        val profile = arguments?.getString("image")
        receiverUid = arguments?.getString("uid")

        binding.name.text = name
        Glide.with(requireActivity()).load(profile)
            .placeholder(R.drawable.avatar)
            .into(binding.profile01)

        viewModel.bindToGetReceiverStatus(receiverUid!!) { status ->
            if (status == "Offline") {
                binding.status.visibility = View.GONE
            } else {
                binding.status.text = status
                binding.status.visibility = View.VISIBLE
            }
        }
    }

    private fun textWatcher(handler: Handler) = object : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence,
            start: Int,
            count: Int,
            after: Int
        ) {
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            viewModel.setUserPresence(PresenceUseCaseFirebase.PRESENCE_TYPING)
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed(userStoppedTyping, 1000)
        }

        var userStoppedTyping =
            Runnable {
                viewModel.setUserPresence(PresenceUseCaseFirebase.PRESENCE_ONLINE)
            }
    }


    private fun grantCameraPermission() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) -> {
                Log.d("TAG", "Camera permissions granted already")
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun getFileUri(): Uri {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(Date())
        val imageFileName = "JPEG_" + timeStamp + "_"
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val image = File.createTempFile(
            imageFileName,  /* prefix */
            ".jpg",  /* suffix */
            storageDir /* directory */
        )
        return FileProvider.getUriForFile(requireContext(), "ua.opu.continent.fileprovider", image)
    }

    private fun savePhotoFirebase(it: Uri) {
        dialog = ProgressDialog.newInstance("Uploading image...")
        dialog.show(parentFragmentManager, "uplImg")

        val messageCreateDto = MessageCreateDto.Builder()
            .uriPhoto(it)
            .senderRoom(senderRoom!!)
            .receiverRoom(receiverRoom!!)
            .onCompleteListener { dialog.dismiss() }
            .build()


        viewModel.sendMessagePhoto(messageCreateDto)
    }

    override fun onResume() {
        super.onResume()
        viewModel.setUserPresence(PresenceUseCaseFirebase.PRESENCE_ONLINE)
    }

    override fun onPause() {
        super.onPause()
        viewModel.setUserPresence(PresenceUseCaseFirebase.PRESENCE_OFFLINE)
    }

}

